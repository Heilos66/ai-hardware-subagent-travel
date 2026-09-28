import asyncio
import base64
import json

import httpx
import pytest
from pydantic import SecretStr

from wenlv_agent.clients import (
    KooSearchClient,
    ModelClient,
    normalize_model_response,
    parse_classification,
)
from wenlv_agent.errors import AgentError
from wenlv_agent.images import prepare_image
from wenlv_agent.schemas import ChatInputs, ModelConfig


@pytest.mark.parametrize(
    "raw,expected", [("1%", 0.01), ("0.5%", 0.005), ("70%", 0.7), ("72.47", 0.7247), (0.9, 0.9)]
)
def test_percent_confidence(raw, expected):
    result = parse_classification(
        {
            "code": 200,
            "data": {
                "top1_result": {
                    "class_name": "test",
                    "confidence": raw,
                }
            },
        }
    )
    assert result.confidence == expected


@pytest.mark.parametrize("raw", [None, "NaN", "inf", -1, "101%", True, "broken"])
def test_invalid_confidence_is_no_match(raw):
    result = parse_classification(
        {
            "code": 200,
            "data": {
                "top1_result": {
                    "class_name": "test",
                    "confidence": raw,
                }
            },
        }
    )
    assert not result.name


@pytest.mark.parametrize("code", [0, "0"])
def test_numeric_success_code(code):
    assert normalize_model_response({"code": code, "message": "answer"}) == {
        "code": "0",
        "message": "answer",
    }


def test_reasoning_content_excluded():
    result = normalize_model_response(
        {
            "choices": [
                {
                    "message": {
                        "content": "answer",
                        "reasoning_content": "private",
                    }
                }
            ]
        }
    )
    assert result == {"code": "0", "message": "answer"}


@pytest.mark.parametrize(
    "payload",
    [
        {},
        {"choices": []},
        {"code": False, "message": "bad"},
        {"choices": [{"message": {"content": []}}]},
    ],
)
def test_invalid_model_outputs(payload):
    with pytest.raises(AgentError) as error:
        normalize_model_response(payload)
    assert error.value.code == "E5003"


@pytest.mark.parametrize("wrapper", ["raw", "url_safe", "data"])
def test_base64_normalized_once(wrapper):
    image = b"\xff\xd8\xff\xfb\xef"
    raw = base64.b64encode(image).decode()
    encoded = raw
    if wrapper == "url_safe":
        encoded = base64.urlsafe_b64encode(image).decode().rstrip("=")
    elif wrapper == "data":
        encoded = f"data:image/jpeg;base64,{raw}"
    result = prepare_image(ChatInputs(image_base64=[encoded]), 100)
    assert result.base64 == raw
    assert result.vision_url == f"data:image/jpeg;base64,{raw}"


@pytest.mark.parametrize(
    "fields",
    [
        {"image_url": ["ftp://example.test/a.jpg"]},
        {"image_url": ["https://example.test/a.gif"]},
        {"image_url": ["https://example.test/a.jpg"], "image_base64": ["x"]},
        {"image_base64": ["broken!"]},
        {"image_base64": ["data:image/png;base64"]},
        {"image_base64": [base64.b64encode(b"hello").decode()]},
    ],
)
def test_invalid_images(fields):
    with pytest.raises(AgentError):
        prepare_image(ChatInputs(**fields), 100)


async def test_iam_token_rotation_and_published_url(settings, tmp_path):
    token_file = tmp_path / "token"
    token_file.write_text("first-token")
    config = settings.model_copy(
        update={
            "koosearch_appcode": SecretStr(""),
            "koosearch_token_file": str(token_file),
            "koosearch_url": "https://published.example.test/custom/search?env=prod",
            "koosearch_text_threshold": 12.5,
        }
    )
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json={"doc_list": []})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        client = KooSearchClient(http, config)
        await client.search("问题")
        token_file.write_text("second-token\n")
        await client.search("问题")
    assert [r.headers["X-Auth-Token"] for r in requests] == ["first-token", "second-token"]
    assert all("X-Apig-AppCode" not in r.headers for r in requests)
    assert str(requests[0].url) == config.koosearch_url
    assert json.loads(requests[0].content)["search_threshold"] == 12.5


async def test_proxy_endpoint_and_body(settings, request_body):
    raw = request_body["inputs"]["model_config"][0]
    raw.update(
        provider="convaigateway", endpoint="https://proxy.example.test/prefix?endpoint=Latest"
    )
    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(200, json={"code": 0, "message": "ok"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        result = [
            part async for part in ModelClient(http, settings).generate(ModelConfig(**raw), [])
        ]
    assert result == ["ok"]
    assert (
        str(seen[0].url)
        == "https://proxy.example.test/prefix/runtimes/test-model/invocations/stream?endpoint=Latest"
    )
    assert "Authorization" not in seen[0].headers
    body = json.loads(seen[0].content)
    assert body["temp_token"] == "model-secret"
    assert body["action"] == "invoke_text"
    assert isinstance(body["model_request"], dict)
    assert body["model_request"]["stream"] is True
    assert seen[0].headers["Accept"] == "text/event-stream"


async def test_model_allowed_hosts(settings, request_body):
    config = settings.model_copy(update={"model_allowed_hosts": "approved.example.test"})

    def no_request(request):
        pytest.fail("Should reject the endpoint before HTTP")

    async with httpx.AsyncClient(transport=httpx.MockTransport(no_request)) as http:
        with pytest.raises(AgentError) as error:
            async for _ in ModelClient(http, config).generate(
                ModelConfig(**request_body["inputs"]["model_config"][0]), []
            ):
                pass
    assert error.value.code == "400"


async def test_search_overall_timeout_cancels_request(settings):
    cancelled = asyncio.Event()

    async def slow(request):
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.set()

    config = settings.model_copy(update={"search_timeout": 0.01})
    async with httpx.AsyncClient(transport=httpx.MockTransport(slow)) as http:
        with pytest.raises(AgentError) as error:
            await KooSearchClient(http, config).search("test")
    assert error.value.code == "E5002"
    assert cancelled.is_set()
