import json
from contextlib import asynccontextmanager

import httpx
import pytest

from wenlv_agent.agent import TravelAgent
from wenlv_agent.app import create_app
from wenlv_agent.clients import KooSearchClient, MobileNetClient, ModelClient
from wenlv_agent.config import Settings


@pytest.fixture
def settings():
    return Settings(
        koosearch_endpoint="https://search.example.test",
        koosearch_project_id="project1",
        koosearch_application_id="app1",
        koosearch_text_repo_id="text-repo",
        koosearch_image_repo_id="image-repo",
        koosearch_appcode="search-secret",
        classifier_url="https://classifier.example.test/mobilenetv3large",
        classifier_api_key="classifier-secret",
    )


@pytest.fixture
def request_body():
    return {
        "inputs": {
            "operation": "chat_completions",
            "query": "介绍这个展品",
            "model_config": [
                {
                    "type": kind,
                    "provider": "openai-compatible",
                    "endpoint": f"https://{kind}.example.test/v1/chat/completions?version=1",
                    "model_name": "test-model",
                    "temperature": "0.1",
                    "auth_token": "model-secret",
                }
                for kind in ("text", "text_vision")
            ],
        }
    }


@pytest.fixture
def harness(settings):
    @asynccontextmanager
    async def build(handler, config=None):
        config = config or settings
        requests = []

        async def record(request):
            requests.append(request)
            return await handler(request)

        async with httpx.AsyncClient(transport=httpx.MockTransport(record)) as upstream:
            agent = TravelAgent(
                config,
                MobileNetClient(upstream, config),
                KooSearchClient(upstream, config),
                ModelClient(upstream, config),
            )
            app = create_app(config, agent)
            async with app.router.lifespan_context(app):
                async with httpx.AsyncClient(
                    transport=httpx.ASGITransport(app=app), base_url="http://test"
                ) as client:
                    yield client, requests

    return build


def frames(response):
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    result = []
    for block in response.text.split("\n\n"):
        if not block or block.startswith(":"):
            continue
        lines = block.splitlines()
        name = next(line.removeprefix("event: ") for line in lines if line.startswith("event:"))
        data = json.loads(next(line[6:] for line in lines if line.startswith("data: ")))
        assert name == data["event"]
        assert isinstance(data["createdTime"], int)
        result.append(data)
    assert result[0]["event"] == "workflow_started"
    assert [frame["event"] for frame in result[-3:]] == ["message", "workflow_finished", "end"]
    final = result[-2]["data"]["outputs"]["responseContent"]
    assert result[-3]["data"]["text"] == final
    assert result[-3]["data"]["reasoning_content"] == ""
    return result, json.loads(final)


async def success(request):
    if request.url.host == "classifier.example.test":
        return httpx.Response(
            200,
            json={
                "code": 200,
                "data": {"top1_result": {"class_name": "兵马俑", "confidence": "85%"}},
            },
        )
    if request.url.host == "search.example.test":
        return httpx.Response(
            200,
            json={
                "doc_list": [{"title": "兵马俑", "content": "秦代陶俑。", "score": 0.9}],
                "total": 1,
            },
        )
    return httpx.Response(
        200,
        json={
            "choices": [{"message": {"content": "这是秦代陶俑。", "reasoning_content": "隐藏思考"}}]
        },
    )
