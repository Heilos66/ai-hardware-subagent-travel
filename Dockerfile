# Build with: docker buildx build --platform linux/arm64 -t wenlv-agent:0.1.0 --load .
FROM python:3.12-slim AS builder
WORKDIR /app
RUN pip install --no-cache-dir uv==0.12.10
COPY pyproject.toml uv.lock ./
COPY src ./src
RUN uv sync --frozen --no-dev --no-editable --no-cache

FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PATH="/app/.venv/bin:$PATH"
WORKDIR /app
RUN useradd --create-home --uid 10001 agent
COPY --from=builder --chown=agent:agent /app/.venv /app/.venv
USER agent
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/ping', timeout=2)"
CMD ["uvicorn", "wenlv_agent.app:app", "--host", "0.0.0.0", "--port", "8080", "--no-access-log"]
