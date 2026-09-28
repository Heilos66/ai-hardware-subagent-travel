import asyncio
import json
import logging
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import aclosing, suppress

from .agent import TravelAgent
from .config import Settings
from .errors import AgentError
from .schemas import Result

logger = logging.getLogger(__name__)


def now_ms() -> int:
    return time.time_ns() // 1_000_000


def event(name: str, data: dict | None = None) -> str:
    body = {"event": name, "createdTime": now_ms()}
    if data is not None:
        body["data"] = data
    return f"event: {name}\ndata: {json.dumps(body, ensure_ascii=False)}\n\n"


async def workflow_events(
    agent: TravelAgent, payload: object, settings: Settings
) -> AsyncIterator[str]:
    execution_id = str(uuid.uuid4())
    start = now_ms()
    yield event("workflow_started", {"start_time": start})
    index = 0
    result = None
    failed = False
    try:
        async with aclosing(agent.run(payload)) as steps:
            async for step in steps:
                if isinstance(step, Result):
                    result = step.content
                    continue
                index += 1
                yield event(
                    "node_started",
                    {
                        "agent_id": settings.workflow_id,
                        "node_id": step.id,
                        "node_status": "node_started",
                        "parent_workflow_id": "",
                        "status": {"code": 0, "desc": "succeeded"},
                        "node_name": step.name,
                        "node_type": step.type,
                        "inputs": {},
                        "outputs": {},
                        "start_time": now_ms(),
                        "execution_id": execution_id,
                    },
                )
        if result is None:
            raise AgentError("E5004", "工作流未生成结果")
    except AgentError as exc:
        failed = True
        result = exc.result()
    except Exception as exc:
        failed = True
        # Never log exception text/traceback: SDK errors can contain request secrets.
        logger.error("workflow_failed execution_id=%s type=%s", execution_id, type(exc).__name__)
        result = {"code": "E5004", "message": "内部处理异常"}
    serialized = json.dumps(result, ensure_ascii=False)
    yield event(
        "message",
        {
            "text": serialized,
            "reasoning_content": "",
            "index": index + 1,
            "node_id": "node_end",
            "node_type": "End",
            "node_name": "结束",
            "workflow_id": settings.workflow_id,
            "workflow_name": settings.workflow_name,
            "createdTime": now_ms(),
        },
    )
    yield event(
        "workflow_finished",
        {
            "status": {"code": -1 if failed else 1, "desc": "failed" if failed else "succeeded"},
            "outputs": {"responseContent": serialized},
            "metadata": {"enable_history": False},
            "start_time": start,
            "end_time": now_ms(),
            "execution_id": execution_id,
        },
    )
    yield event("end")


async def with_heartbeat(source: AsyncIterator[str], interval: float) -> AsyncIterator[str]:
    """Send SSE comments while I/O is pending; cancel that I/O on disconnect."""
    pending = None
    try:
        while True:
            if pending is None:
                pending = asyncio.create_task(anext(source))
            done, _ = await asyncio.wait({pending}, timeout=interval)
            if not done:
                yield ": keep-alive\n\n"
                continue
            try:
                item = pending.result()
            except StopAsyncIteration:
                return
            pending = None
            yield item
    finally:
        if pending is not None:
            pending.cancel()
            with suppress(asyncio.CancelledError, StopAsyncIteration):
                await pending
        await source.aclose()
