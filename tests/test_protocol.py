import asyncio
import json

import pytest
from conftest import frames, success

from wenlv_agent.events import with_heartbeat


@pytest.mark.parametrize("path", ["/invocations", "/stream", "/invocations/stream"])
async def test_capabilities_and_routes_without_external_calls(harness, path):
    async with harness(success) as (client, requests):
        response = await client.post(path, json={"inputs": {"operation": "query_capabilities"}})
        health = await client.get("/ping")
    assert health.json() == {"status": "Healthy"}
    assert not requests
    assert frames(response)[1] == {
        "capabilities": {
            "chat_completions": True,
            "create_response": False,
            "fetch_response": False,
        }
    }


@pytest.mark.parametrize("payload", [None, [], {}, {"inputs": []}, {"inputs": {"operation": []}}])
async def test_malformed_envelope(harness, payload):
    async with harness(success) as (client, requests):
        response = await client.post(
            "/invocations",
            content=json.dumps(payload),
            headers={"Content-Type": "application/json"},
        )
    assert frames(response)[1]["code"] == "400"
    assert not requests


@pytest.mark.parametrize("operation", ["create_response", "fetch_response", "unknown"])
async def test_unsupported_operations(harness, operation):
    async with harness(success) as (client, requests):
        response = await client.post("/invocations", json={"inputs": {"operation": operation}})
    assert frames(response)[1]["code"] == "400"
    assert not requests


@pytest.mark.parametrize("query,ok", [("a" * 1000, True), ("a" * 1001, False), ("  ", False)])
async def test_query_length(harness, request_body, query, ok):
    request_body["inputs"]["query"] = query
    async with harness(success) as (client, requests):
        response = await client.post("/invocations", json=request_body)
    assert (frames(response)[1]["code"] == "0") is ok
    assert bool(requests) is ok


async def test_input_validation_does_not_echo_secret(harness, request_body):
    request_body["inputs"]["model_config"][0]["temperature"] = "model-secret"
    async with harness(success) as (client, requests):
        response = await client.post("/invocations", json=request_body)
    assert frames(response)[1]["code"] == "400"
    assert "model-secret" not in response.text
    assert not requests


async def test_transport_errors(harness, settings):
    async with harness(success, settings.model_copy(update={"max_request_bytes": 10})) as (
        client,
        requests,
    ):
        response = await client.post(
            "/invocations", content="bad json", headers={"Content-Type": "application/json"}
        )
        assert response.status_code == 400
        response = await client.post("/invocations", content="hello")
        assert response.status_code == 415
        response = await client.post("/invocations", json={"inputs": {}})
        assert response.status_code == 413
    assert not requests


async def test_heartbeat_and_pending_call_cancelled_on_close():
    cancelled = asyncio.Event()

    async def slow():
        try:
            yield "first"
            await asyncio.Event().wait()
        finally:
            cancelled.set()

    stream = with_heartbeat(slow(), 0.01)
    assert await anext(stream) == "first"
    assert await anext(stream) == ": keep-alive\n\n"
    await stream.aclose()
    assert cancelled.is_set()
