"""Decode SSE framing independently of HTTP/network chunk boundaries."""

from collections.abc import AsyncIterator

from .errors import AgentError


async def sse_events(lines: AsyncIterator[str]) -> AsyncIterator[tuple[str, str]]:
    data: list[str] = []
    name = "message"
    size = 0
    first = True
    async for line in lines:
        if first:
            line = line.removeprefix("\ufeff")
            first = False
        if not line:
            if data or name == "error":
                yield name, "\n".join(data)
            data, name, size = [], "message", 0
            continue
        if line.startswith(":"):
            continue
        field, _, value = line.partition(":")
        value = value.removeprefix(" ")
        if field == "data":
            size += len(value) + 1
            if size > 1024 * 1024:
                raise AgentError("E5003", "模型流式事件过大")
            data.append(value)
        elif field == "event":
            name = value
    # Some providers omit the last blank line. Completion is checked by the caller.
    if data or name == "error":
        yield name, "\n".join(data)
