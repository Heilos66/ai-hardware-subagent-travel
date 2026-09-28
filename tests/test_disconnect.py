import asyncio

import pytest
from starlette.requests import ClientDisconnect

from wenlv_agent.app import WorkflowStreamingResponse
from wenlv_agent.events import with_heartbeat


@pytest.mark.parametrize("version", ["2.3", "2.4"])
async def test_asgi_disconnect_closes_pending_work(version):
    cancelled = asyncio.Event()
    started = asyncio.Event()
    disconnect = asyncio.Event()

    async def source():
        try:
            yield "event: start\n\n"
            started.set()
            await asyncio.Event().wait()
        finally:
            cancelled.set()

    async def send(message):
        if message.get("body") == b": keep-alive\n\n":
            if version == "2.4":
                raise OSError("client disconnected")
            disconnect.set()

    async def receive():
        await disconnect.wait()
        return {"type": "http.disconnect"}

    response = WorkflowStreamingResponse(with_heartbeat(source(), 0.01))
    async with asyncio.timeout(1):
        if version == "2.4":
            with pytest.raises(ClientDisconnect):
                await response({"type": "http", "asgi": {"spec_version": version}}, receive, send)
        else:
            await response({"type": "http", "asgi": {"spec_version": version}}, receive, send)
    assert started.is_set()
    assert cancelled.is_set()
