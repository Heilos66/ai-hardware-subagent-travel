import asyncio
import json
from contextlib import aclosing

import httpx
import pytest
from conftest import frames, success
from starlette.requests import ClientDisconnect

from wenlv_agent.agent import TravelAgent
from wenlv_agent.app import WorkflowStreamingResponse
from wenlv_agent.clients import KooSearchClient, MobileNetClient, ModelClient
from wenlv_agent.events import with_heartbeat, workflow_events
from wenlv_agent.schemas import ModelConfig


def delta(text):
    return (
        "data: "
        + json.dumps({"choices": [{"index": 0, "delta": {"content": text}}]}, ensure_ascii=False)
        + "\n\n"
    )


class ChunkStream(httpx.AsyncByteStream):
    def __init__(self, chunks):
        self.chunks = chunks
        self.closed = False

    async def __aiter__(self):
        for chunk in self.chunks:
            yield chunk

    async def aclose(self):
        self.closed = True


async def test_utf8_boundaries_crlf_comments_and_multiline(settings, request_body):
    data = (
        "\ufeff: heartbeat\r\n\r\n"
        "event: message\r\nid: 1\r\n"
        'data: {"choices":\r\n'
        'data: [{"index":0,"delta":{"content":"中文"}}]}\r\n\r\n'
        + delta(" and 日本語\n")
        + "data: [DONE]\n\n"
    ).encode()
    # Deliberately split every multibyte code point and every line/frame boundary.
    stream = ChunkStream([bytes([byte]) for byte in data])
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda r: httpx.Response(
                200, headers={"Content-Type": "text/event-stream; charset=utf-8"}, stream=stream
            )
        )
    ) as client:
        parts = [
            part
            async for part in ModelClient(client, settings).generate(
                ModelConfig(**request_body["inputs"]["model_config"][0]), []
            )
        ]
    assert parts == ["中文", " and 日本語\n"]
    assert stream.closed


@pytest.mark.parametrize(
    "tail",
    [
        "",  # Clean EOF without a completion marker is still an interrupted generation.
        "event: error\ndata: secret-upstream-error\n\n",
        'data: {"error":{"message":"secret-upstream-error"}}\n\n',
        "data: invalid-secret-upstream-error\n\n",
        'data: {"choices":[{"delta":{"content":123}}]}\n\n',
    ],
)
async def test_midstream_failures_do_not_report_partial_answer_as_success(
    harness, request_body, tail
):
    async def handler(request):
        if request.url.host == "search.example.test":
            return await success(request)
        return httpx.Response(
            200, headers={"Content-Type": "text/event-stream"}, content=delta("部分回答") + tail
        )

    async with harness(handler) as (client, _):
        response = await client.post("/invocations", json=request_body)
    events, result = frames(response)
    assert result["code"] == "E5003"
    assert events[-2]["data"]["status"]["desc"] == "failed"
    assert [e["data"]["text"] for e in events if e["event"] == "message"] == ["部分回答"]
    assert "secret-upstream-error" not in response.text


@pytest.mark.parametrize(
    "tail",
    [
        "data: [DONE]\n\n",
        'data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}',
    ],
)
async def test_supported_completion_markers(harness, request_body, tail):
    async def handler(request):
        if request.url.host == "search.example.test":
            return await success(request)
        return httpx.Response(
            200, headers={"Content-Type": "text/event-stream"}, content=delta(" 保留空格 ") + tail
        )

    async with harness(handler) as (client, _):
        response = await client.post("/invocations", json=request_body)
    assert frames(response)[1] == {"code": "0", "message": " 保留空格 "}


async def test_reasoning_only_is_not_an_answer(harness, request_body):
    async def handler(request):
        if request.url.host == "search.example.test":
            return await success(request)
        return httpx.Response(
            200,
            headers={"Content-Type": "text/event-stream"},
            content=(
                'data: {"choices":[{"delta":{"reasoning_content":"secret-thought"}}]}\n\n'
                "data: [DONE]\n\n"
            ),
        )

    async with harness(handler) as (client, _):
        response = await client.post("/invocations", json=request_body)
    assert frames(response)[1]["code"] == "E5003"
    assert "secret-thought" not in response.text


class PausedStream(httpx.AsyncByteStream):
    def __init__(self):
        self.release = asyncio.Event()
        self.closed = False

    async def __aiter__(self):
        yield delta("第一段").encode()
        await self.release.wait()
        yield (delta("第二段") + "data: [DONE]\n\n").encode()

    async def aclose(self):
        self.closed = True


def make_agent(http, settings):
    return TravelAgent(
        settings,
        MobileNetClient(http, settings),
        KooSearchClient(http, settings),
        ModelClient(http, settings),
    )


async def test_first_delta_reaches_asgi_before_second_upstream_chunk(settings, request_body):
    stream = PausedStream()

    async def handler(request):
        if request.url.host == "search.example.test":
            return await success(request)
        return httpx.Response(200, headers={"Content-Type": "text/event-stream"}, stream=stream)

    received = []

    async def send(message):
        data = message.get("body", b"").decode()
        if data.startswith("event: message"):
            frame = json.loads(data.split("data: ", 1)[1])
            received.append(frame["data"]["text"])
            if received == ["第一段"]:
                assert not stream.release.is_set()
                stream.release.set()  # Upstream cannot finish before this reaches ASGI.

    async def receive():
        await asyncio.Event().wait()

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        response = WorkflowStreamingResponse(
            with_heartbeat(
                workflow_events(make_agent(http, settings), request_body, settings), 0.01
            )
        )
        async with asyncio.timeout(1):
            await response({"type": "http", "asgi": {"spec_version": "2.4"}}, receive, send)
    assert received == ["第一段", "第二段"]
    assert stream.closed


async def test_timeout_across_heartbeat_tasks_and_upstream_closed(settings, request_body):
    stream = PausedStream()
    settings = settings.model_copy(update={"model_timeout": 0.03})

    async def handler(request):
        if request.url.host == "search.example.test":
            return await success(request)
        return httpx.Response(200, headers={"Content-Type": "text/event-stream"}, stream=stream)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        async with aclosing(
            with_heartbeat(
                workflow_events(make_agent(http, settings), request_body, settings), 0.005
            )
        ) as events:
            async with asyncio.timeout(1):
                result = "".join([part async for part in events])
    assert "第一段" in result and "第二段" not in result
    assert "E5003" in result and "模型服务调用超时" in result
    assert "event: end" in result
    assert stream.closed


async def test_cancel_after_delta_closes_upstream_immediately(settings, request_body):
    stream = PausedStream()
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda r: httpx.Response(
                200, headers={"Content-Type": "text/event-stream"}, stream=stream
            )
        )
    ) as http:
        async with aclosing(
            ModelClient(http, settings).generate(
                ModelConfig(**request_body["inputs"]["model_config"][0]), []
            )
        ) as parts:
            assert await anext(parts) == "第一段"
    assert stream.closed


@pytest.mark.parametrize("version", ["2.3", "2.4"])
async def test_downstream_disconnect_closes_model_stream(settings, request_body, version):
    stream = PausedStream()
    disconnected = asyncio.Event()

    async def handler(request):
        if request.url.host == "search.example.test":
            return await success(request)
        return httpx.Response(200, headers={"Content-Type": "text/event-stream"}, stream=stream)

    async def send(message):
        if message.get("body") == b": keep-alive\n\n":
            if version == "2.4":
                raise OSError("disconnected")
            disconnected.set()

    async def receive():
        await disconnected.wait()
        return {"type": "http.disconnect"}

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        response = WorkflowStreamingResponse(
            with_heartbeat(
                workflow_events(make_agent(http, settings), request_body, settings), 0.005
            )
        )
        async with asyncio.timeout(1):
            if version == "2.4":
                with pytest.raises(ClientDisconnect):
                    await response(
                        {"type": "http", "asgi": {"spec_version": version}}, receive, send
                    )
            else:
                await response({"type": "http", "asgi": {"spec_version": version}}, receive, send)
    assert stream.closed
