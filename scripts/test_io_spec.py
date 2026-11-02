#!/usr/bin/env python3
# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""Send references/io_spec.md's JSON body to the local Agent without printing credentials."""

import argparse
import http.client
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

SSE_SIZE_LIMIT = 16 * 1024 * 1024


def read_request(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    match = re.search(r"--data(?:-raw|-binary)?\s+'", text)
    if not match:
        raise ValueError("未找到 --data ' 开始的内联 JSON 请求体")
    body, _ = json.JSONDecoder().raw_decode(text[match.end() :].lstrip())
    if not isinstance(body, dict) or not isinstance(body.get("inputs"), dict):
        raise ValueError("请求体必须包含 inputs 对象")
    return body


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="本地测试 io_spec.md 请求体，实时显示 SSE")
    parser.add_argument(
        "--spec",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "references" / "io_spec.md",
        help="本地 IO 文档路径，默认 references/io_spec.md",
    )
    parser.add_argument("--port", type=int, default=8080, help="本机服务端口，默认 8080")
    parser.add_argument("--check", action="store_true", help="仅校验请求体，不发送请求或输出凭证")
    parser.add_argument(
        "--capabilities", action="store_true", help="只查询能力，不调用任何外部服务"
    )
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not 1 <= args.port <= 65535:
        parser.error("端口须为 1～65535")
    try:
        body = (
            {"inputs": {"operation": "query_capabilities"}}
            if args.capabilities
            else read_request(args.spec)
        )
    except (OSError, UnicodeError, ValueError) as exc:
        # JSON decoding diagnostics can contain source text; do not print them.
        print(
            f"请求体读取或解析失败（{type(exc).__name__}），请检查文件及 JSON 格式。",
            file=sys.stderr,
        )
        return 1
    if args.check:
        print("请求体解析成功，包含 inputs 对象。未发送请求，未输出凭证。")
        return 0

    return invoke(body, args.port, args.capabilities)


def invoke(body: dict, port: int, capabilities: bool) -> int:
    url = f"http://127.0.0.1:{port}/invocations"
    print(f"POST {url}（仅发送 JSON 请求体，不转发文档中的网关鉴权头）", flush=True)
    if not capabilities:
        print("这是实际问答测试，将调用已配置的分类、检索或模型服务。", flush=True)
    request = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
        method="POST",
    )
    # Ignore system proxies for this loopback-only request.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=150) as response:
            print(
                f"HTTP {response.status} · {response.headers.get('Content-Type', '')}", flush=True
            )
            return consume_sse(response)
    except urllib.error.HTTPError as exc:
        code = exc.code
        exc.close()
        print(f"本地接口返回 HTTP {code}，请检查请求格式。", file=sys.stderr)
        return 1
    except (urllib.error.URLError, TimeoutError, OSError):
        print("连接失败或中断，请确认本地服务已启动、端口正确。", file=sys.stderr)
        return 1
    except (ValueError, KeyError, TypeError):
        print("响应不符合约定的 SSE 格式。", file=sys.stderr)
        return 1


def consume_sse(response: http.client.HTTPResponse) -> int:
    business_error = False
    finished = False
    ended = False
    size = 0
    sse_limit = SSE_SIZE_LIMIT
    for raw in response:
        size += len(raw)
        if size > sse_limit:
            print("SSE 响应过大，已中止。", file=sys.stderr)
            return 1
        line = raw.decode("utf-8")
        print(line, end="", flush=True)
        if not line.startswith("data:"):
            continue
        frame = json.loads(line.removeprefix("data:").strip())
        if frame.get("event") == "workflow_finished":
            data = frame["data"]
            result = json.loads(data["outputs"]["responseContent"])
            business_error = str(data.get("status", {}).get("code")) != "1"
            if "code" in result:
                business_error |= str(result["code"]) != "0"
            finished = True
        if frame.get("event") == "end":
            ended = True
    if not finished or not ended:
        print("响应未完整结束：缺少 workflow_finished 或 end。", file=sys.stderr)
        return 1
    return 1 if business_error else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
