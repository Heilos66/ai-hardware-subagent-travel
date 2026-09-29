import asyncio
import json
import math
import re
from collections.abc import AsyncIterator
from contextlib import aclosing
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

import httpx

from .config import Settings
from .errors import AgentError
from .images import ImageInput
from .schemas import Classification, KnowledgeChunk, ModelConfig, TextDelta
from .streaming import sse_events


async def post_json(
    client: httpx.AsyncClient,
    url: str,
    *,
    headers: dict[str, str],
    body: dict,
    timeout_seconds: float,
    service: str,
    error_code: str,
) -> dict:
    """Keep transport details, URLs, tokens and response bodies out of public errors."""
    try:
        # Overall deadline in addition to HTTPX's per-I/O timeout.
        async with asyncio.timeout(timeout_seconds):
            response = await client.post(
                url, headers=headers, json=body, timeout=timeout_seconds, follow_redirects=False
            )
    except (TimeoutError, httpx.TimeoutException):
        raise AgentError(error_code, f"{service}调用超时") from None
    except httpx.HTTPError:
        raise AgentError(error_code, f"{service}连接失败") from None
    if not 200 <= response.status_code < 300:
        if service == "图片分类服务" and response.status_code == 401:
            raise AgentError("401", "鉴权失败")
        if service == "图片分类服务" and 400 <= response.status_code < 500:
            raise AgentError("400", "请求参数错误")
        raise AgentError(error_code, f"{service}返回HTTP {response.status_code}")
    try:
        data = response.json()
    except (ValueError, UnicodeError):
        raise AgentError(error_code, f"{service}响应不是有效JSON") from None
    if not isinstance(data, dict):
        raise AgentError(error_code, f"{service}响应必须是JSON对象")
    return data


def parse_classification(body: dict) -> Classification:
    """Malformed classification is a no-match, as in the original workflow."""
    if str(body.get("code")) != "200":
        return Classification()
    data = body.get("data")
    top = data.get("top1_result") if isinstance(data, dict) else None
    if not isinstance(top, dict) or not isinstance(top.get("class_name"), str):
        return Classification()
    name = top["class_name"].strip()
    raw = top.get("confidence")
    if not name or isinstance(raw, bool) or raw is None:
        return Classification()
    try:
        text = str(raw).strip()
        percent = text.endswith("%")
        confidence = float(text[:-1] if percent else text)
        if percent or confidence > 1:
            confidence /= 100
        if not math.isfinite(confidence) or not 0 <= confidence <= 1:
            return Classification()
    except (TypeError, ValueError):
        return Classification()
    # Do not round before applying the threshold: 0.69999 must remain a no-match.
    return Classification(name=name, confidence=confidence)


class MobileNetClient:
    def __init__(self, client: httpx.AsyncClient, settings: Settings):
        self.client = client
        self.settings = settings

    async def classify(self, image: ImageInput) -> Classification:
        key = self.settings.classifier_api_key.get_secret_value().strip()
        if not key or key.startswith("E1-{kms-"):
            raise AgentError("E5004", "图片分类服务密钥未配置")
        body = await post_json(
            self.client,
            self.settings.classifier_url,
            headers={"X-API-Key": key},
            body={"image_url": image.url, "image_base64": image.base64},
            timeout_seconds=self.settings.classifier_timeout,
            service="图片分类服务",
            error_code="500",
        )
        return parse_classification(body)


class KooSearchClient:
    """SearchInKnowledgeRepo (current API), with an optional published APIG URL."""

    def __init__(self, client: httpx.AsyncClient, settings: Settings):
        self.client = client
        self.settings = settings

    async def _auth_headers(self) -> dict[str, str]:
        settings = self.settings
        if settings.koosearch_appcode.get_secret_value():
            return {"X-Apig-AppCode": settings.koosearch_appcode.get_secret_value()}
        token = settings.koosearch_token.get_secret_value().strip()
        if settings.koosearch_token_file:
            try:
                token = (
                    await asyncio.to_thread(
                        Path(settings.koosearch_token_file).read_text, encoding="utf-8"
                    )
                ).strip()
            except (OSError, UnicodeError):
                raise AgentError("E5004", "Koo Search Token文件不可读取") from None
        if not token:
            raise AgentError("E5004", "Koo Search认证未配置")
        return {"X-Auth-Token": token}

    async def search(self, query: str, *, image: bool = False) -> list[KnowledgeChunk]:
        settings = self.settings
        repo = settings.koosearch_image_repo_id if image else settings.koosearch_text_repo_id
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", repo):
            raise AgentError("E5004", "Koo Search知识库ID未配置或格式错误")
        if not query.strip() or len(query) > 1000:
            raise AgentError("400", "检索内容必须为1至1000个字符")
        body = {
            "repo_id": repo,
            "content": query,
            "scope": "doc",
            "page_num": 1,
            "page_size": settings.koosearch_top_k,
        }
        threshold = (
            settings.koosearch_image_threshold if image else settings.koosearch_text_threshold
        )
        if threshold is not None:
            body["search_threshold"] = threshold
        response = await post_json(
            self.client,
            settings.search_url(),
            headers=await self._auth_headers(),
            body=body,
            timeout_seconds=settings.search_timeout,
            service="知识检索服务",
            error_code="E5002",
        )
        docs = response.get("doc_list")
        if not isinstance(docs, list):
            raise AgentError("E5002", "知识检索响应缺少doc_list")
        chunks = []
        for doc in docs[: settings.koosearch_top_k]:
            if not isinstance(doc, dict) or not isinstance(doc.get("content"), str):
                raise AgentError("E5002", "知识检索分片格式错误")
            content = doc["content"].strip()
            if not content:
                continue
            score = doc.get("score")
            if isinstance(score, bool) or not isinstance(score, (int, float)):
                score = None
            elif not math.isfinite(score):
                score = None
            chunks.append(
                KnowledgeChunk(
                    content=content,
                    title=str(doc.get("title") or ""),
                    subtitle=str(doc.get("subtitle") or ""),
                    score=score,
                )
            )
        return chunks


def normalize_model_response(body: dict) -> dict[str, str]:
    if "code" in body:
        code = body["code"]
        if not isinstance(code, bool) and code in (0, "0"):
            message = body.get("message")
            if isinstance(message, str) and message.strip():
                result = {"code": "0", "message": message.strip()}
                reasoning = body.get("reasoning_content")
                if reasoning is not None and not isinstance(reasoning, str):
                    raise AgentError("E5003", "模型响应reasoning_content格式错误")
                if reasoning:
                    result["reasoning_content"] = reasoning
                return result
        # Upstream diagnostics may contain tokens/request bodies. Do not forward them.
        raise AgentError("E5003", "模型服务返回业务错误")
    choices = body.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
        raise AgentError("E5003", "模型响应缺少choices")
    message = choices[0].get("message")
    content = message.get("content") if isinstance(message, dict) else None
    if not isinstance(content, str) or not content.strip():
        raise AgentError("E5003", "模型响应message.content为空或格式错误")
    result = {"code": "0", "message": content.strip()}
    reasoning = message.get("reasoning_content")
    if reasoning is not None and not isinstance(reasoning, str):
        raise AgentError("E5003", "模型响应reasoning_content格式错误")
    if reasoning:
        result["reasoning_content"] = reasoning
    return result


class ModelClient:
    def __init__(self, client: httpx.AsyncClient, settings: Settings):
        self.client = client
        self.settings = settings

    async def generate(self, config: ModelConfig, messages: list[dict]) -> AsyncIterator[TextDelta]:
        endpoint = config.endpoint
        parts = urlsplit(endpoint)
        allowed = {
            host.strip().lower()
            for host in self.settings.model_allowed_hosts.split(",")
            if host.strip()
        }
        if allowed and parts.hostname.lower() not in allowed:
            raise AgentError("400", "模型endpoint不在允许的主机列表中")
        vision = config.type == "text_vision"
        model_request = {
            "model": config.model_name,
            "messages": messages,
            "temperature": config.temperature,
            "stream": True,
        }
        for field in ("chat_template_kwargs", "thinking"):
            value = getattr(config, field)
            if value is not None:
                model_request[field] = value
        token = config.auth_token.get_secret_value().strip()
        headers = {"Accept": "text/event-stream", "User-Agent": "travel-agent/1.0"}
        if config.provider.lower() == "openai-compatible":
            headers["Authorization"] = (
                token if token.lower().startswith("bearer ") else f"Bearer {token}"
            )
            body = model_request
        else:
            # Preserve the old proxy protocol for existing non-OpenAI providers.
            path = parts.path.rstrip("/")
            if path.endswith("/invocations"):
                path += "/stream"
            elif not path.endswith("/invocations/stream"):
                if not re.fullmatch(r"[A-Za-z0-9._-]+", config.model_name):
                    raise AgentError("400", "model_name包含不支持的字符")
                path += f"/runtimes/{config.model_name}/invocations/stream"
            endpoint = urlunsplit(parts._replace(path=path))
            body = {
                "temp_token": token,
                "action": "invoke_text_vision" if vision else "invoke_text",
                "model_request": model_request,
            }
        # Each anext() can run in a different task (SSE heartbeats). Never keep an
        # asyncio.timeout context open across a yield: its cancellation binds to a task.
        deadline = asyncio.get_running_loop().time() + self.settings.model_timeout
        response = None
        try:
            request = self.client.build_request(
                "POST", endpoint, headers=headers, json=body, timeout=self.settings.model_timeout
            )
            async with asyncio.timeout_at(deadline):
                response = await self.client.send(request, stream=True, follow_redirects=False)
            if not 200 <= response.status_code < 300:
                raise AgentError("E5003", f"模型服务返回HTTP {response.status_code}")
            media_type = response.headers.get("content-type", "").split(";", 1)[0].strip().lower()
            if media_type != "text/event-stream":
                # Retain compatibility with gateways which ignore stream=true and return JSON.
                async with asyncio.timeout_at(deadline):
                    await response.aread()
                try:
                    result = response.json()
                except (ValueError, UnicodeError):
                    raise AgentError("E5003", "模型响应不是有效JSON或SSE") from None
                if not isinstance(result, dict):
                    raise AgentError("E5003", "模型响应必须是JSON对象")
                normalized = normalize_model_response(result)
                yield TextDelta(normalized["message"], normalized.get("reasoning_content", ""))
                return

            response.encoding = "utf-8"
            completed = False
            has_text = False
            async with aclosing(sse_events(response.aiter_lines())) as events:
                while True:
                    try:
                        async with asyncio.timeout_at(deadline):
                            name, data = await anext(events)
                    except StopAsyncIteration:
                        break
                    if name == "error":
                        raise AgentError("E5003", "模型服务返回流式错误")
                    if data.strip() == "[DONE]":
                        completed = True
                        break
                    if not data.strip():
                        continue
                    try:
                        chunk = json.loads(data)
                    except (ValueError, UnicodeError):
                        raise AgentError("E5003", "模型流式数据不是有效JSON") from None
                    if not isinstance(chunk, dict):
                        raise AgentError("E5003", "模型流式数据格式错误")
                    if "error" in chunk or "code" in chunk:
                        raise AgentError("E5003", "模型服务返回流式错误")
                    choices = chunk.get("choices")
                    if choices == [] and "usage" in chunk:
                        continue
                    if not isinstance(choices, list) or not choices:
                        raise AgentError("E5003", "模型流式数据缺少choices")
                    for choice in choices:
                        if not isinstance(choice, dict):
                            raise AgentError("E5003", "模型流式choice格式错误")
                        if choice.get("index", 0) != 0:
                            continue
                        delta = choice.get("delta")
                        if not isinstance(delta, dict):
                            raise AgentError("E5003", "模型流式数据缺少delta")
                        reason = choice.get("finish_reason")
                        if reason == "content_filter":
                            raise AgentError("E5003", "模型回答被内容过滤")
                        content = delta.get("content")
                        reasoning = delta.get("reasoning_content")
                        if content is not None and not isinstance(content, str):
                            raise AgentError("E5003", "模型流式content格式错误")
                        if reasoning is not None and not isinstance(reasoning, str):
                            raise AgentError("E5003", "模型流式reasoning_content格式错误")
                        if content or reasoning:
                            has_text = has_text or bool(content and content.strip())
                            yield TextDelta(content or "", reasoning or "")
                        # Role/tool metadata does not belong to answer or reasoning text.
                        if isinstance(reason, str) and reason:
                            completed = True
            if not completed:
                raise AgentError("E5003", "模型流式响应中断，未收到结束标记")
            if not has_text:
                raise AgentError("E5003", "模型响应message.content为空")
        except (TimeoutError, httpx.TimeoutException):
            raise AgentError("E5003", "模型服务调用超时") from None
        except httpx.HTTPError:
            raise AgentError("E5003", "模型服务连接失败或流式响应中断") from None
        finally:
            if response is not None:
                await response.aclose()
