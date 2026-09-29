import json

import httpx
import pytest
from conftest import frames, success


async def test_text_search_and_model_request(harness, request_body):
    async with harness(success) as (client, requests):
        response = await client.post(
            "/invocations", json=request_body, headers={"Authorization": "Bearer runtime-secret"}
        )
    events, result = frames(response)
    assert result == {"code": "0", "message": "这是秦代陶俑。"}
    assert events[-2]["data"]["status"] == {"code": 1, "desc": "succeeded"}
    assert len(requests) == 2
    search, model = requests
    assert str(search.url) == (
        "https://search.example.test/v1/project1/applications/app1/uni-search/experience/searchtext"
    )
    assert search.headers["X-Apig-AppCode"] == "search-secret"
    assert "Authorization" not in search.headers
    assert json.loads(search.content) == {
        "repo_id": "text-repo",
        "content": "介绍这个展品",
        "page_num": 1,
        "page_size": 3,
        "scope": "doc",
    }
    assert str(model.url) == "https://text.example.test/v1/chat/completions?version=1"
    assert model.headers["Authorization"] == "Bearer model-secret"
    body = json.loads(model.content)
    assert body["stream"] is True
    assert model.headers["Accept"] == "text/event-stream"
    messages = [e["data"] for e in events if e["event"] == "message"]
    assert [message["text"] for message in messages] == ["", "这是", "秦代陶俑。"]
    assert [message["reasoning_content"] for message in messages] == ["隐藏思考", "", ""]
    assert body["temperature"] == 0.1
    assert "秦代陶俑" in body["messages"][1]["content"]
    assert "隐藏思考" not in result["message"]
    for secret in ("runtime-secret", "model-secret", "search-secret"):
        assert secret not in response.text


async def test_image_match_uses_image_repo_and_text_model(harness, request_body):
    request_body["inputs"]["image_url"] = [
        "https://image.test/first.jpg",
        "https://image.test/second.jpg",
    ]
    request_body["inputs"]["query"] = "これはなんですか？"
    async with harness(success) as (client, requests):
        response = await client.post("/invocations", json=request_body)
    events, result = frames(response)
    assert result["code"] == "0"
    assert [r.url.host for r in requests] == [
        "classifier.example.test",
        "search.example.test",
        "text.example.test",
    ]
    assert json.loads(requests[0].content) == {
        "image_url": "https://image.test/first.jpg",
        "image_base64": "",
    }
    assert requests[0].headers["X-API-Key"] == "classifier-secret"
    assert json.loads(requests[1].content)["repo_id"] == "image-repo"
    assert json.loads(requests[1].content)["content"] == "检索关于兵马俑的文档"
    prompt = json.loads(requests[2].content)["messages"][1]["content"]
    assert "これはなんですか？" in prompt
    assert "图片识别候选名称：兵马俑" in prompt
    assert any(e.get("data", {}).get("node_id") == "search_image" for e in events)


@pytest.mark.parametrize(
    "confidence,host", [("70%", "text"), (0.69999, "text_vision"), ("1%", "text_vision")]
)
async def test_classification_threshold(harness, request_body, confidence, host):
    request_body["inputs"]["image_url"] = ["https://image.test/test.jpg"]

    async def handler(request):
        if request.url.host == "classifier.example.test":
            return httpx.Response(
                200,
                json={
                    "code": 200,
                    "data": {
                        "top1_result": {
                            "class_name": "兵马俑",
                            "confidence": confidence,
                        }
                    },
                },
            )
        return await success(request)

    async with harness(handler) as (client, requests):
        response = await client.post("/invocations", json=request_body)
    assert frames(response)[1]["code"] == "0"
    assert requests[-1].url.host == f"{host}.example.test"
    assert json.loads(requests[-1].content)["stream"] is True
    if host == "text_vision":
        assert len(requests) == 2
        content = json.loads(requests[-1].content)["messages"][1]["content"]
        assert content[1] == {
            "type": "image_url",
            "image_url": {"url": "https://image.test/test.jpg"},
        }


async def test_no_search_results_preserves_exhibit_name(harness, request_body):
    request_body["inputs"]["image_url"] = ["https://image.test/test.jpg"]
    request_body["inputs"]["query"] = ""

    async def handler(request):
        if request.url.host == "search.example.test":
            return httpx.Response(200, json={"doc_list": [], "total": 0})
        return await success(request)

    async with harness(handler) as (client, requests):
        response = await client.post("/invocations", json=request_body)
    assert frames(response)[1]["code"] == "0"
    prompt = json.loads(requests[-1].content)["messages"][1]["content"]
    assert "兵马俑" in prompt
    assert "当前没有可用的内部资料" in prompt
    assert "请简要介绍这个景点或展品" in prompt


@pytest.mark.parametrize("status,code", [(400, "400"), (401, "401"), (500, "500")])
async def test_classifier_http_failure_stops_without_vision(harness, request_body, status, code):
    request_body["inputs"]["image_url"] = ["https://image.test/test.jpg"]

    async def handler(request):
        return httpx.Response(status, json={"error": "private-secret"})

    async with harness(handler) as (client, requests):
        response = await client.post("/invocations", json=request_body)
    events, result = frames(response)
    assert result["code"] == code
    assert len(requests) == 1
    assert events[-2]["data"]["status"]["desc"] == "failed"
    assert "private-secret" not in response.text


@pytest.mark.parametrize("failure", ["http", "timeout", "malformed", "missing_docs"])
async def test_search_failure_does_not_silently_generate(harness, request_body, failure):
    async def handler(request):
        if failure == "timeout":
            raise httpx.ReadTimeout("secret search URL", request=request)
        if failure == "malformed":
            return httpx.Response(200, text="<html>secret</html>")
        if failure == "missing_docs":
            return httpx.Response(200, json={"error_code": "upstream-error", "error_msg": "secret"})
        return httpx.Response(503, text="secret")

    async with harness(handler) as (client, requests):
        response = await client.post("/invocations", json=request_body)
    assert frames(response)[1]["code"] == "E5002"
    assert len(requests) == 1
    assert "secret" not in response.text


@pytest.mark.parametrize("failure", ["timeout", "http", "invalid_json", "empty", "error"])
async def test_model_failure_is_safe_and_finishes(harness, request_body, failure):
    async def handler(request):
        if request.url.host == "search.example.test":
            return await success(request)
        if failure == "timeout":
            raise httpx.ReadTimeout("model-secret", request=request)
        if failure == "http":
            return httpx.Response(500, text="model-secret")
        if failure == "invalid_json":
            return httpx.Response(200, text="model-secret")
        if failure == "empty":
            return httpx.Response(200, json={"choices": [{"message": {"content": ""}}]})
        return httpx.Response(200, json={"code": 500, "message": "model-secret"})

    async with harness(handler) as (client, requests):
        response = await client.post("/invocations", json=request_body)
    assert frames(response)[1]["code"] == "E5003"
    assert "model-secret" not in response.text
    assert len(requests) == 2


async def test_missing_vision_model_does_not_use_text_model(harness, request_body):
    request_body["inputs"]["model_config"] = request_body["inputs"]["model_config"][:1]
    request_body["inputs"]["image_url"] = ["https://image.test/test.jpg"]

    async def handler(request):
        return httpx.Response(200, json={"code": 200, "data": {"top1_result": {}}})

    async with harness(handler) as (client, requests):
        response = await client.post("/invocations", json=request_body)
    assert frames(response)[1] == {"code": "400", "message": "模型参数缺少：text_vision"}
    assert len(requests) == 1
