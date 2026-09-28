import json
import logging
from contextlib import asynccontextmanager

import httpx
from anyio import CancelScope
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

from .agent import TravelAgent
from .clients import KooSearchClient, MobileNetClient, ModelClient
from .config import Settings
from .events import with_heartbeat, workflow_events


class WorkflowStreamingResponse(StreamingResponse):
    async def stream_response(self, send):
        try:
            await super().stream_response(send)
        finally:
            # ASGI 2.4 send failures do not automatically close the body iterator.
            # Shield cleanup from the older ASGI disconnect task's cancel scope too.
            with CancelScope(shield=True):
                await self.body_iterator.aclose()


def create_app(settings: Settings | None = None, agent: TravelAgent | None = None) -> FastAPI:
    settings = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        # HTTPX info logs include URLs, which can contain signed query strings.
        logging.getLogger("httpx").setLevel(logging.WARNING)
        logging.getLogger("httpcore").setLevel(logging.WARNING)
        if agent is not None:
            app.state.agent = agent
            yield
        else:
            async with httpx.AsyncClient(
                limits=httpx.Limits(max_connections=100, max_keepalive_connections=20),
                follow_redirects=False,
            ) as client:
                app.state.agent = TravelAgent(
                    settings,
                    MobileNetClient(client, settings),
                    KooSearchClient(client, settings),
                    ModelClient(client, settings),
                )
                yield

    app = FastAPI(title="文旅 Agent", version="0.1.0", lifespan=lifespan)

    @app.get("/ping")
    async def ping():
        return {"status": "Healthy"}

    @app.post("/invocations", response_class=StreamingResponse)
    @app.post("/stream", response_class=StreamingResponse, include_in_schema=False)
    @app.post("/invocations/stream", response_class=StreamingResponse, include_in_schema=False)
    async def invoke(request: Request):
        # AgentArts authenticates the outer request. Never forward that credential.
        media_type = request.headers.get("content-type", "").split(";", 1)[0].lower().strip()
        if media_type != "application/json":
            return JSONResponse({"code": "400", "message": "需要application/json"}, status_code=415)
        data = bytearray()
        async for chunk in request.stream():
            if len(data) + len(chunk) > settings.max_request_bytes:
                return JSONResponse({"code": "400", "message": "请求体大小超限"}, status_code=413)
            data.extend(chunk)
        try:
            payload = json.loads(data)
        except (ValueError, UnicodeError, RecursionError):
            return JSONResponse({"code": "400", "message": "请求体不是有效JSON"}, status_code=400)
        frames = workflow_events(request.app.state.agent, payload, settings)
        return WorkflowStreamingResponse(
            with_heartbeat(frames, settings.heartbeat_seconds),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    return app


app = create_app()
