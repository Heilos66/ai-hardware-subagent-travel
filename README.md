# 文旅 Agent · AgentArts 高代码版

将原「文旅逛展_1」低代码流程实现为 Python 服务，通过 ARM64 容器部署到 AgentArts 高代码运行时。保留 MobileNet 分类、0.7 阈值和三条问答路径，知识检索替换为 Koo Search。

```text
inputs → 参数校验
  ├─ query_capabilities → 能力结果
  └─ chat_completions / 空 operation
       ├─ 无图片 → Koo Search 文本库 → 文本模型
       └─ 有图片 → MobileNet
            ├─ 类别非空且置信度 ≥ 0.7 → Koo Search 图片库 → 文本模型
            └─ 低置信度 / 无有效分类 → 视觉模型
结果 → message → workflow_finished → end
```

分类 HTTP 失败直接返回错误；检索失败也返回错误。检索成功但无分片时，模型按“不确定时不编造”的提示词回答。模型 endpoint 使用请求中的完整地址。

## 本地运行

需要 Python 3.12+ 和 uv。依赖版本在 `uv.lock` 中锁定。

```bash
uv sync --frozen
cp .env.example .env
# 编辑 .env，填入服务配置；启动时显式加载：
set -a
. ./.env
set +a
uv run --frozen uvicorn wenlv_agent.app:app --host 0.0.0.0 --port 8080 --no-access-log
```

不配置外部服务也能启动并测试健康检查与能力查询：

```bash
curl http://localhost:8080/ping
curl -N http://localhost:8080/invocations \
  -H 'Content-Type: application/json' \
  -d '{"inputs":{"operation":"query_capabilities"}}'
```

### 直接测试 io_spec.md 的请求体

服务启动后，在另一个终端执行：

```bash
# 只校验 Markdown 中的 JSON，不显示凭证、不调用外部服务：
python3 scripts/test_io_spec.py --check
# 只测试本地 HTTP/SSE，无需外部服务配置：
python3 scripts/test_io_spec.py --capabilities
# 读取 --data 后的完整请求体并发送到本地 8080：
python3 scripts/test_io_spec.py
```

脚本不复制请求体到新文件，也不转发 Markdown 中的 AgentArts 网关鉴权头，实时显示 SSE 帧。可用 `--port 8081` 修改本机目标端口。实际问答会调用配置的 MobileNet、Koo Search 或模型服务；缺少配置时会返回相应业务错误。仅看到 HTTP 200 不表示问答成功，需检查 `workflow_finished.data.outputs.responseContent` 内的 `code` 是否为 `"0"`。脚本在业务失败或响应不完整时以非零状态退出。

## 服务配置

配置示例见 `.env.example`。服务不自动加载该文件；在 AgentArts 中使用运行时环境变量。

| 配置 | 说明 |
| --- | --- |
| `KOOSEARCH_URL` | 实例公布的完整检索 URL；设置后优先使用 |
| `KOOSEARCH_ENDPOINT`、`KOOSEARCH_PROJECT_ID`、`KOOSEARCH_APPLICATION_ID` | 未设完整 URL 时，拼接当前 SearchInKnowledgeRepo 路径 |
| `KOOSEARCH_TEXT_REPO_ID`、`KOOSEARCH_IMAGE_REPO_ID` | 两条检索路径的知识库 ID；可填同一个库 |
| `KOOSEARCH_APPCODE` | APP 简易认证凭证 |
| `KOOSEARCH_TOKEN` / `KOOSEARCH_TOKEN_FILE` | 替代 AppCode 的项目级 IAM Token；文件值优先于环境值，每次检索重读 |
| `KOOSEARCH_TOP_K` | 默认 3 |
| `KOOSEARCH_TEXT_THRESHOLD`、`KOOSEARCH_IMAGE_THRESHOLD` | 可选；默认不传，由 Koo Search 知识库决定 |
| `CLASSIFIER_URL` | 默认保留原 MobileNet 地址和 `/mobilenetv3large` 路径 |
| `CLASSIFIER_API_KEY` | 分类服务原始可用密钥，作为 `X-API-Key` 发送 |
| `CLASSIFIER_THRESHOLD` | 默认 0.7 |
| `CLASSIFIER_TIMEOUT`、`SEARCH_TIMEOUT`、`MODEL_TIMEOUT` | 单次调用总超时，默认 30/30/60 秒 |
| `MODEL_ALLOWED_HOSTS` | 可选，逗号分隔的模型目标主机限制 |

原导出文件中的 `E1-{kms-...}` 是平台加密值，不能当成分类 API 原始密钥。原知识库 ID 也不会自动变成 Koo Search ID。IAM Token 由部署环境负责在过期前更新，或者选择已开通的 AppCode 认证。

Koo Search 使用官方 [SearchInKnowledgeRepo](https://support.huaweicloud.com/api-koosearch/SearchInKnowledgeRepo.html)；实际服务地址须从实例获取。完整依据见 [Koo Search 接口核实](docs/koosearch-research.md)。

## 请求与 SSE

请求保留 `io_spec.md` 的 `inputs` 外层。模型配置仍由调用方传入，温度兼容数字或数字字符串。以下均为占位值：

```json
{
  "inputs": {
    "operation": "chat_completions",
    "query": "介绍一下这件展品",
    "image_url": ["https://example.com/exhibit.jpg"],
    "model_config": [
      {
        "type": "text",
        "provider": "openai-compatible",
        "endpoint": "https://your-model-host/v1/chat/completions",
        "model_name": "your-text-model",
        "temperature": 0.1,
        "auth_token": "REPLACE_AT_CALL_TIME"
      },
      {
        "type": "text_vision",
        "provider": "openai-compatible",
        "endpoint": "https://your-model-host/v1/chat/completions",
        "model_name": "your-vision-model",
        "temperature": 0.1,
        "auth_token": "REPLACE_AT_CALL_TIME"
      }
    ]
  }
}
```

- 纯文本问答省略 `image_url`；图片也可由 `image_base64` 数组提供，两种形式互斥。沿用只处理数组第一张图片的行为。
- `operation` 缺省、null 或空字符串等同于 `chat_completions`；不支持 `create_response` 与 `fetch_response`。
- 文本最多 1000 字符；有图片时可不填问题。Base64 支持纯编码、URL-safe 及 data URI，统一转为 JPG/PNG 编码。
- 默认请求体限制 16 MiB、解码图片限制 10 MiB，均可配置。图片 URL 只做协议和扩展名校验，实际下载与解码由分类服务完成；Base64 检查文件头，与原流程一致。
- `provider=openai-compatible`：直接调用完整 endpoint，使用 Bearer token；其他 provider 延用原网关的 `temp_token/action/model_request` 协议，并支持完整 invocations 地址或基础地址。
- 不发送会话历史。原流程虽然开启历史记录，但模型入参没有引用历史；本实现保持单轮行为，`metadata.enable_history=false`。

`POST /invocations` 固定返回 SSE，顺序为 `workflow_started`、若干 `node_started`、`message`、`workflow_finished`、`end`。过程节点使用新的稳定 ID，不模拟原来的 32 个低代码节点。过程帧不输出请求内容和凭证；每 15 秒可发送 SSE 注释心跳。

上游模型使用非流式请求，与原流程一致。收到完整结果后发送一个 `message`，其 `data.text` 等于最终 JSON 字符串；**不是逐 token 生成**。最终结果从 `workflow_finished.data.outputs.responseContent` 获取，反序列化后为：

```json
{"code":"0","message":"展品讲解内容"}
```

能力查询结果为 `{"capabilities":{"chat_completions":true,"create_response":false,"fetch_response":false}}`。`reasoning_content` 始终为空，上游的同名字段不进入结果。

业务错误仍通过 SSE 返回并以 `end` 收尾。`400/401/500` 沿用原有输入和分类错误；`E5002` 为知识检索失败，`E5003` 为模型失败，`E5004` 为服务配置或内部错误。错误不会透传上游响应正文。成功的外层 `status` 使用样例中的 `code=1, desc=succeeded`；失败扩展定义为 `code=-1, desc=failed`，调用方应读取内层业务 code。非法 JSON、错误 Content-Type、超大请求分别直接返回 HTTP 400/415/413 JSON。

## AgentArts 部署

官方要求 ARM64、`0.0.0.0:8080`、`POST /invocations`，本项目实现了这些接口及 `GET /ping`。平台负责入口鉴权；服务镜像不校验外层平台 token，也不将其转发给下游。[运行时文档核实](docs/agentarts-runtime-research.md)

1. 构建 ARM64 镜像：

   ```bash
   docker buildx build --platform linux/arm64 -t wenlv-agent:0.1.0 --load .
   docker image inspect wenlv-agent:0.1.0 --format '{{.Architecture}}'
   docker run --rm -p 8080:8080 --env-file .env wenlv-agent:0.1.0
   ```

2. 使用实际 SWR 仓库地址给镜像打标签并推送。在 AgentArts 控制台创建高代码运行时，选择镜像；端口设为 8080，使用镜像默认启动命令，配置环境变量及平台鉴权。
3. 为运行时配置到 Koo Search、MobileNet 和模型 endpoint 的网络连通性。标准 SSE 调用只需 `/invocations`；若使用平台 `/invocations/stream`，启用 `PREFIX_MATCH`，平台转发到容器 `/stream`。项目也保留了本地 `/invocations/stream` 别名。
4. 使用控制台返回的真实网关和运行时名称调用：

   ```bash
   curl -N "${AGENTARTS_GATEWAY}/runtimes/${RUNTIME_NAME}/invocations?endpoint=Latest" \
     -H "Authorization: Bearer ${AGENTARTS_API_KEY}" \
     -H "X-Hw-Agentarts-Session-Id: ${SESSION_ID}" \
     -H 'Content-Type: application/json' \
     --data-binary @request.json
   ```

   上例适用于运行时 API Key 鉴权；IAM 调用按平台签名规则处理。`request.json` 按上面的占位示例自行填写，不提交凭证。

镜像只包含安装后的应用与依赖，原 HTML、JSONL、`io_spec.md`、`.env` 均不会进入构建上下文。容器无持久化状态。部署前需要已有 Koo Search 知识库及可用认证；本项目不负责导入文档或创建云资源。

## 验证与代码结构

```bash
uv run --frozen pytest -q
uv run --frozen ruff check src tests
uv run --frozen ruff format --check src tests
```

测试使用 HTTP MockTransport 验证完整请求链及 ASGI/SSE 协议，不消耗真实模型额度。云端验收还需使用真实服务确认分类响应、检索分片、模型结果和 SSE 网关转发。

| 文件 | 职责 |
| --- | --- |
| `src/wenlv_agent/app.py` | AgentArts HTTP 入口、健康检查、连接池 |
| `src/wenlv_agent/agent.py` | 三条业务路径与能力查询 |
| `src/wenlv_agent/clients.py` | MobileNet、Koo Search、模型协议 |
| `src/wenlv_agent/events.py` | SSE 事件、心跳、断开取消 |
| `src/wenlv_agent/images.py` | 图片输入规范化 |
| `src/wenlv_agent/prompts.py` | 文旅讲解提示词 |
| `src/wenlv_agent/config.py`、`schemas.py` | 部署配置与请求契约 |

迁移决策见 [MIGRATION.md](MIGRATION.md)。原始资料保留原样。
