import json

import httpx
import pytest
from conftest import frames, model_sse, success

from wenlv_agent.clients import ModelClient
from wenlv_agent.schemas import ModelConfig


@pytest.mark.parametrize("kind", ["text", "text_vision"])
@pytest.mark.parametrize("provider", ["openai-compatible", "convaigateway"])
@pytest.mark.parametrize(
    "options,expected",
    [
        ({}, {}),
        ({"thinking": None}, {}),
        ({"thinking": {}}, {"thinking": {}}),
        (
            {"thinking": {"type": "enabled", "budget_tokens": 1024}},
            {"thinking": {"type": "enabled", "budget_tokens": 1024}},
        ),
        (
            {"chat_template_kwargs": {"thinking": False, "other": {"value": [1, True]}}},
            {"chat_template_kwargs": {"thinking": False, "other": {"value": [1, True]}}},
        ),
        ({"thinking": '{"type":"disabled"}'}, {"thinking": {"type": "disabled"}}),
        (
            {"chat_template_kwargs": '{"thinking":true}'},
            {"chat_template_kwargs": {"thinking": True}},
        ),
        (
            {"thinking": {"type": "enabled"}, "chat_template_kwargs": {"thinking": True}},
            {"thinking": {"type": "enabled"}, "chat_template_kwargs": {"thinking": True}},
        ),
    ],
)
async def test_model_options_transmitted_as_objects(
    settings, request_body, kind, provider, options, expected
):
    config = ModelConfig(
        **{
            **request_body["inputs"]["model_config"][0],
            "type": kind,
            "provider": provider,
            **options,
        }
    )
    requests = []

    def handler(request):
        requests.append(request)
        return model_sse("回答")

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        parts = [part async for part in ModelClient(http, settings).generate(config, [])]
    assert "".join(part.text for part in parts) == "回答"
    body = json.loads(requests[0].content)
    if provider != "openai-compatible":
        body = body["model_request"]
    assert body["stream"] is True
    assert {
        key: body[key] for key in ("thinking", "chat_template_kwargs") if key in body
    } == expected


@pytest.mark.parametrize("field", ["thinking", "chat_template_kwargs"])
@pytest.mark.parametrize("value", [True, [], "true", "[]", "null", "bad-json", '{"value":NaN}'])
async def test_invalid_options_fail_at_entry_without_request(harness, request_body, field, value):
    request_body["inputs"]["model_config"][0][field] = value
    async with harness(success) as (client, requests):
        response = await client.post("/invocations", json=request_body)
    assert frames(response)[1]["code"] == "400"
    assert not requests


@pytest.mark.parametrize("stream", [True, False])
async def test_reasoning_and_answer_reach_separate_output_fields(harness, request_body, stream):
    request_body["inputs"]["model_config"][0]["chat_template_kwargs"] = '{"thinking":true}'

    async def handler(request):
        if request.url.host == "search.example.test":
            return await success(request)
        assert json.loads(request.content)["chat_template_kwargs"] == {"thinking": True}
        if not stream:
            return httpx.Response(
                200,
                json={
                    "choices": [
                        {
                            "message": {
                                "content": "讲解正文",
                                "reasoning_content": "分析片段",
                            }
                        }
                    ]
                },
            )
        data = {
            "choices": [
                {
                    "delta": {
                        "content": "讲解正文",
                        "reasoning_content": "分析片段",
                    }
                }
            ]
        }
        return httpx.Response(
            200,
            headers={"Content-Type": "text/event-stream"},
            content=f"data: {json.dumps(data)}\n\ndata: [DONE]\n\n",
        )

    async with harness(handler) as (client, _):
        response = await client.post("/invocations", json=request_body)
    events, result = frames(response)
    messages = [e["data"] for e in events if e["event"] == "message"]
    assert len(messages) == 1
    assert messages[0]["text"] == "讲解正文"
    assert messages[0]["reasoning_content"] == "分析片段"
    assert result == {"code": "0", "message": "讲解正文"}
