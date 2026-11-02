#!/usr/bin/env python3
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""Invoke an IAM AgentArts runtime with Huawei's official V11 signer and SSE."""

import argparse
import http.client
import importlib
import io
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from types import ModuleType
from typing import Any, Iterator, Optional
from pathlib import Path

from test_io_spec import read_request


@dataclass(frozen=True)
class SigningContext:
    region: str
    session_id: str
    ak: str = field(repr=False)
    sk: str = field(repr=False)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(
        self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str
    ) -> None:
        # A redirect changes the signed target and must never forward credentials.
        return None


def load_signer(sdk_dir: Optional[Path]) -> ModuleType:
    if sdk_dir:
        if not (sdk_dir / "apig_sdk" / "signer.py").is_file():
            raise ValueError("SDK 目录必须直接包含 apig_sdk/signer.py")
        sys.path.insert(0, str(sdk_dir.resolve()))
    try:
        return importlib.import_module("apig_sdk.signer")
    except ImportError:
        raise ValueError("未找到官方签名 SDK；请按 docs/verification.md 配置 APIG_SDK_PATH") from None


def validate_url(url: str) -> None:
    parsed = urllib.parse.urlsplit(url)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or not parsed.path
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
        or not url.isascii()
        or any(ch.isspace() or ord(ch) < 32 or ord(ch) == 127 for ch in url)
    ):
        raise ValueError("请使用控制台的完整 HTTPS 调用 URL，不含用户密码或片段；特殊字符须 URL 编码")
    # Trigger validation of a malformed port before attempting a connection.
    _ = parsed.port


def signed_request(
    signer: ModuleType,
    url: str,
    body: dict,
    context: SigningContext,
    invalid_signature: bool = False,
) -> urllib.request.Request:
    sig = signer.Signer(algorithm="V11-HMAC-SHA256", region_id=context.region)
    sig.Key, sig.Secret = context.ak, context.sk
    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        "X-Sdk-Content-Sha256": "UNSIGNED-PAYLOAD",
        "X-Hw-Agentarts-Session-Id": context.session_id,
    }
    req = signer.HttpRequest("POST", url, headers, json.dumps(body, ensure_ascii=False))
    sig.Sign(req)
    if invalid_signature:
        auth = req.headers["Authorization"]
        replacement = "0" if auth[-1] != "0" else "1"
        req.headers["Authorization"] = auth[:-1] + replacement
    # HttpRequest.uri contains ONLY the path; dropping query would change the endpoint.
    query = signer.CanonicalQueryString(req)
    wire_url = f"{req.scheme}://{req.host}{req.uri}" + (f"?{query}" if query else "")
    return urllib.request.Request(wire_url, data=req.body, headers=req.headers, method="POST")


def sse_frames(response: http.client.HTTPResponse) -> Iterator[dict]:
    """Print each line immediately; parse SSE data only at the event boundary."""
    data = []
    size = 0
    limit = 16 * 1024 * 1024
    # io.TextIOWrapper buffers and decodes at UTF-8 character boundaries, so a readline that
    # splits a multi-byte sequence no longer raises UnicodeDecodeError. Track bytes via
    # len(raw.encode("utf-8")) to preserve the original size-limit semantics.
    text_stream = io.TextIOWrapper(response, encoding="utf-8")
    try:
        while raw := text_stream.readline(limit + 1):
            size += len(raw.encode("utf-8"))
            if size > limit:
                raise ValueError("SSE event too large")
            print(raw, end="", flush=True)
            line = raw.rstrip("\r\n")
            if not line:
                if data:
                    frame = json.loads("\n".join(data))
                    if not isinstance(frame, dict):
                        raise ValueError("SSE data must be an object")
                    yield frame
                data, size = [], 0
            elif line.startswith("data:"):
                data.append(line[5:].removeprefix(" "))
        if data:
            raise ValueError("SSE event truncated")
    finally:
        # detach so closing the wrapper does not close the underlying HTTPResponse owned by caller.
        text_stream.detach()


def consume_sse(response: http.client.HTTPResponse, operation: Optional[str]) -> int:
    finished = False
    business_error = False
    for frame in sse_frames(response):
        if frame.get("event") == "workflow_finished":
            if finished:
                raise ValueError("Duplicate workflow_finished")
            data = frame["data"]
            result = json.loads(data["outputs"]["responseContent"])
            if not isinstance(result, dict):
                raise ValueError("Invalid responseContent")
            business_error = str(data["status"]["code"]) != "1"
            if "code" in result:
                business_error |= str(result["code"]) != "0"
            elif operation != "query_capabilities" or not isinstance(result.get("capabilities"), dict):
                raise ValueError("Missing business result")
            if operation == "query_capabilities" and not business_error:
                if result.get("capabilities", {}).get("chat_completions") is not True:
                    raise ValueError("Missing chat capability")
            finished = True
        elif frame.get("event") == "end":
            if not finished:
                raise ValueError("end before workflow_finished")
            if business_error:
                print("请求已到达应用，但业务执行失败；请查看上方 responseContent 和运行时日志。", file=sys.stderr)
                return 1
            return 0
    raise ValueError("Missing workflow_finished or end")


def invoke(
    request: urllib.request.Request,
    operation: Optional[str],
    timeout: float,
    expect_auth_rejection: bool = False,
    opener: Optional[urllib.request.OpenerDirector] = None,
) -> int:
    opener = opener or urllib.request.build_opener(NoRedirect())
    start = time.monotonic()
    try:
        with opener.open(request, timeout=timeout) as response:
            print(f"HTTP {response.status}", flush=True)
            if expect_auth_rejection:
                print("反向测试失败：错误签名未收到预期的 HTTP 401/403。", file=sys.stderr)
                return 1
            if response.status != 200 or response.headers.get_content_type() != "text/event-stream":
                print("响应不是 HTTP 200 SSE，请检查运行时地址、版本及服务协议。", file=sys.stderr)
                return 1
            result = consume_sse(response, operation)
            print(f"SSE 完整结束，客户端耗时 {time.monotonic() - start:.3f}s。", flush=True)
            return result
    except urllib.error.HTTPError as exc:
        code = exc.code
        exc.close()  # Do not echo gateway error bodies or authentication headers.
        if code in (401, 403):
            if expect_auth_rejection:
                print(f"反向测试通过：错误签名被拒绝（HTTP {code}）。须结合合法签名成功的结果验收。")
                return 0
            print(f"HTTP {code}：网关认证或授权失败，请核对 IAM 模式、AK/SK、区域、权限及本机时间。", file=sys.stderr)
            return 3
        print(f"HTTP {code}：调用失败；请检查路径、endpoint、运行时状态及网关日志。不会跟随重定向。", file=sys.stderr)
    except (urllib.error.URLError, OSError, http.client.HTTPException):
        print("连接失败、超时或流中断；请检查网络、证书及运行时状态。", file=sys.stderr)
    except (ValueError, KeyError, TypeError):
        print("SSE 格式错误或未完整结束：需要有效的 workflow_finished、responseContent 和 end。", file=sys.stderr)
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="IAM 签名调用 AgentArts；默认仅查询能力，实时输出 SSE")
    parser.add_argument(
        "--url", default=os.getenv("AGENTARTS_RUNTIME_URL"),
        help="完整调用 URL，或 AGENTARTS_RUNTIME_URL",
    )
    parser.add_argument("--region", default=os.getenv("AGENTARTS_REGION", "cn-southwest-2"))
    parser.add_argument(
        "--sdk-dir", type=Path, default=os.getenv("APIG_SDK_PATH"),
        help="直接包含 apig_sdk 的官方 SDK 目录",
    )
    parser.add_argument("--session-id", default=str(uuid.uuid4()), help="默认每次生成新的 UUID")
    parser.add_argument(
        "--timeout", type=float, default=150,
        help="连接/单次读取超时秒数，默认 150；不是总时限",
    )
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--capabilities", action="store_true", help="查询能力（默认），不调用分类器或模型")
    source.add_argument("--spec", type=Path, help="读取 IO Markdown 内的请求体，调用实际业务服务")
    source.add_argument("--json", type=Path, dest="json_file", help="读取 JSON 文件内的请求体，调用实际业务服务")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="仅校验配置并生成签名，不发送请求或输出凭据")
    mode.add_argument(
        "--expect-auth-rejection", action="store_true",
        help="仅用能力查询，故意破坏签名，预期 HTTP 401/403",
    )
    return parser


def signing_context(args: argparse.Namespace, parser: argparse.ArgumentParser) -> SigningContext:
    if args.expect_auth_rejection and (args.spec or args.json_file):
        parser.error("反向认证测试只允许能力查询")
    if not args.url:
        parser.error("请设置 AGENTARTS_RUNTIME_URL 或 --url")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", args.session_id):
        parser.error("Session ID 使用 1～128 位英文字母、数字、下划线或连字符")
    if not re.fullmatch(r"[a-z0-9-]+", args.region):
        parser.error("region 格式错误")
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error("timeout 必须为正数")
    ak = os.getenv("HUAWEICLOUD_SDK_AK", "").strip()
    sk = os.getenv("HUAWEICLOUD_SDK_SK", "").strip()
    if not ak or not sk:
        parser.error("请在调用电脑设置 HUAWEICLOUD_SDK_AK 和 HUAWEICLOUD_SDK_SK")
    # This tool follows the documented long-term AK/SK flow; do not silently drop STS tokens.
    if os.getenv("HUAWEICLOUD_SDK_SECURITY_TOKEN"):
        parser.error("当前脚本使用长期 AK/SK；暂不支持带 Security Token 的临时凭据")
    return SigningContext(args.region, args.session_id, ak, sk)


def prepare_request(
    args: argparse.Namespace, parser: argparse.ArgumentParser, context: SigningContext
) -> tuple[urllib.request.Request, dict]:
    try:
        validate_url(args.url)
        signer = load_signer(args.sdk_dir)
    except ValueError:
        parser.error("URL 或 SDK 配置无效；请使用完整 HTTPS URL，并设置官方 SDK 路径（见验证指南）")
    try:
        if args.spec:
            body = read_request(args.spec)
        elif args.json_file:
            body = json.loads(args.json_file.read_text(encoding="utf-8"))
        else:
            body = {"inputs": {"operation": "query_capabilities"}}
        if not isinstance(body, dict) or not isinstance(body.get("inputs"), dict):
            raise ValueError("Missing inputs")
        request = signed_request(
            signer, args.url, body, context, args.expect_auth_rejection
        )
    except (OSError, ValueError, TypeError):
        # JSON/parser/SDK exceptions may embed the body or credentials.
        parser.error("请求体读取、解析或 V11 签名失败；请检查文件、JSON 格式及 SDK 版本，不输出原始异常")
    return request, body


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    context = signing_context(args, parser)
    request, body = prepare_request(args, parser, context)
    if args.check:
        print("配置、请求体解析和 V11 签名生成成功；未发送请求，未验证云端权限。")
        return 0
    print(f"Session ID: {args.session_id}", flush=True)
    if args.spec or args.json_file:
        print("开始真实业务调用；SSE 将实时输出。", flush=True)
    return invoke(request, body["inputs"].get("operation"), args.timeout, args.expect_auth_rejection)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
