# 文旅 Agent · Spring AI 2.0 / AgentArts

将原「文旅逛展_1」低代码流程实现为 Spring Boot 4.1.1 + Spring AI 2.0.1 服务，通过 ARM64 容器部署到 AgentArts 高代码运行时。保留 MobileNet 分类、0.7 阈值和三条问答路径，知识检索使用 Koo Search。不依赖巫山框架或 AgentArts SDK。

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

需要 JDK 21，设置 `JAVA_HOME` 并将 Java 加入 `PATH`。项目自带 Maven Wrapper，首次构建下载 Maven 3.9.11 和依赖；无需预装 Maven。Spring Boot / Spring AI 版本固定于 `pom.xml`。Windows 使用 `mvnw.cmd`。

```bash
./mvnw verify
test -f .env || cp .env.example .env
# 编辑服务配置后，在启动终端显式加载：
set -a
. ./.env
set +a
java -jar target/travel-agent.jar
```

开发时也可执行 `./mvnw spring-boot:run`。监听端口默认 8080，可用 `PORT=8081` 调整。升级时先停止旧的 Uvicorn 服务，再启动 Java 服务，避免端口冲突。服务配置或代码变更后需重启；请求内的 `model_config` 每次读取，无需重启。IAM Token 文件继续支持每次检索重读。

不配置外部服务也能启动并测试健康检查与能力查询：

```bash
curl http://localhost:8080/ping
curl -N http://localhost:8080/invocations \
  -H 'Content-Type: application/json' \
  -d '{"inputs":{"operation":"query_capabilities"}}'
```

### 直接测试 io_spec.md 的请求体

服务启动后，在另一个终端执行（该可选客户端脚本只用 Python 3 标准库，服务本身不依赖 Python）：

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
        "chat_template_kwargs": {"thinking": true},
        "auth_token": "REPLACE_AT_CALL_TIME"
      },
      {
        "type": "text_vision",
        "provider": "openai-compatible",
        "endpoint": "https://your-model-host/v1/chat/completions",
        "model_name": "your-vision-model",
        "temperature": 0.1,
        "thinking": {"type": "enabled"},
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
- `provider=openai-compatible`：直接调用完整 endpoint，使用 Bearer token；其他 provider 延用原网关的 `temp_token/action/model_request` 协议，使用 `/invocations/stream` 路径。文本、图片检索和视觉分支的模型请求都设置 `stream=true`，Accept 为 `text/event-stream`。
- `model_config` 中可选传入 `chat_template_kwargs` 或 `thinking`。支持 JSON 对象或对象的 JSON 字符串（例如 `"thinking": "{\"type\":\"enabled\"}"`）；解析为对象后按原字段名透传到模型请求，代理模式放在 `model_request` 内。不按 text/text_vision 自动添加或改写思考参数；未传或为 null 时省略，空对象保留，同时传入两个字段时均保留。选择哪种字段及内部取值由调用方按模型接口确定。无效 JSON 或非对象返回参数错误。
- 不发送会话历史。原流程虽然开启历史记录，但模型入参没有引用历史；本实现保持单轮行为，`metadata.enable_history=false`。

`POST /invocations` 固定返回 SSE，顺序为 `workflow_started`、若干 `node_started`、多个 `message`、`workflow_finished`、`end`。失败时可能没有 `message`。过程节点使用新的稳定 ID，不模拟原来的 32 个低代码节点。过程帧不输出请求内容和凭证；每 15 秒可发送 SSE 注释心跳。

模型回答实时按上游 `choices[0].delta.content` 分段返回：每个 `message.data.text` 是新生成的**纯文本增量**，保留原始空格和换行。客户端直接拼接这些 text，不要逐帧解析成业务 JSON。结束时不会再用 `message` 重发全文；完整业务结果仍从 `workflow_finished.data.outputs.responseContent` 获取，反序列化后为：

```json
{"code":"0","message":"展品讲解内容"}
```

上游 `delta.reasoning_content` 同步透传到 `message.data.reasoning_content`，与 `data.text` 分开。仅有思考的片段会返回空 text；同一片段同时有正文与思考时，两者在同一事件中返回。客户端可分别拼接两类字段；最终 `responseContent.message` 只汇总正文。上游没有返回思考时，该字段仍为空，服务不会自行生成思考内容。普通 JSON 响应也保留其 `message.reasoning_content`。只有思考而没有正文的响应仍视为未生成有效回答。

能力查询仍通过单个 `message` 返回 JSON 字符串 `{"capabilities":{"chat_completions":true,"create_response":false,"fetch_response":false}}`，最终汇总相同，其 `reasoning_content` 为空。

支持 SSE 分片跨网络包、UTF-8 字符拆分、CRLF、多行 data、注释心跳、角色/usage 空分片和 `[DONE]`。上游必须通过 `[DONE]` 或有效的 `finish_reason` 表明生成结束；提前断流不会把部分文字当作成功结果。如果上游忽略 `stream=true` 而返回普通 JSON，则兼容为单个文本片段，正文首尾空白同样保留。单个上游 SSE 事件限制为 1 MiB，普通 JSON 响应上限为 16 MiB。模型超时为整次调用总时限，不因持续返回分片而重置；客户端断开或超时都会取消上游调用。接口依据：[华为云 OpenAI 兼容接口](https://support.huaweicloud.com/model-call-maas/model-call-021.html)。

### Spring AI 接入方式

三条业务路径均使用 Spring AI `Prompt`、`SystemMessage`、`UserMessage`（视觉输入使用 `Media`）构建模型输入，通过 `ChatClient.prompt(...).stream().chatResponse()` 调用。项目实现 `CompatibleChatModel` 作为 Spring AI `ChatModel` SPI 适配器，承接完整 endpoint、现有代理包装、非标准精简 SSE、JSON 回退和结束标记校验；网络由 WebClient / Reactor Netty 连接池负责。每次请求创建独立模型配置，不共享认证信息和会话状态。

这里使用的是自定义 `CompatibleChatModel`，没有使用官方 `OpenAiChatModel`。因此适配器的 `reasoningContent` 元数据明确保持**上游增量**，无需将官方实现的累计值再次切片。外部 `message.data.reasoning_content` 继续为增量；回归测试验证了多个思考片段不会重复拼接。未来替换为官方 `OpenAiChatModel` 时，必须重新核对其元数据语义，见[调研记录](docs/agentarts-spring-ai-2-research.md)。

业务错误在 `workflow_finished` 的业务结果中返回，并以 `end` 收尾，不把错误 JSON 混入文本增量。若已经显示部分文字，客户端需根据最终错误 code 将本次回答标为失败。`400/401/500` 沿用原有输入和分类错误；`E5002` 为知识检索失败，`E5003` 为模型失败，`E5004` 为服务配置或内部错误。错误不会透传上游响应正文。成功的外层 `status` 使用样例中的 `code=1, desc=succeeded`；失败扩展定义为 `code=-1, desc=failed`，调用方应读取内层业务 code。非法 JSON、错误 Content-Type、超大请求分别直接返回 HTTP 400/415/413 JSON。

## AgentArts 部署

官方要求 ARM64、`0.0.0.0:8080`、`POST /invocations`，本项目实现了这些接口及 `GET /ping`。平台负责入口鉴权；服务镜像不校验外层平台 token，也不将其转发给下游。[运行时文档核实](docs/agentarts-runtime-research.md)

1. 构建 ARM64 镜像：

   ```bash
   docker buildx build --platform linux/arm64 -t travel-agent:0.2.0 --load .
   docker image inspect travel-agent:0.2.0 --format '{{.Architecture}}'
   docker run --rm -p 8080:8080 --env-file .env travel-agent:0.2.0
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

Dockerfile 使用 JDK 21 构建，再将可执行 JAR 放入 ARM64 JRE 21 镜像，以非 root 用户运行。镜像只包含 JRE、应用和依赖，原 HTML、JSONL、`io_spec.md`、`.env`、测试与本地客户端脚本均不会进入构建上下文。容器无持久化状态。部署前需要已有 Koo Search 知识库及可用认证；本项目不负责导入文档或创建云资源。默认 `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70.0`，可按运行时资源规格覆盖。

## 验证与代码结构

```bash
./mvnw verify
```

JUnit 测试通过本机随机端口模拟分类、检索和模型服务，并使用 WebTestClient 验证 HTTP 入口；不读取 `.env` 或原始请求中的凭证，不消耗真实模型额度。覆盖三条路径、0.7 边界、配置透传、JSON 回退、SSE 分片、思考增量、超时、心跳及取消。云端验收还需使用真实服务确认分类响应、检索分片、模型结果和 SSE 网关转发。

| 文件 | 职责 |
| --- | --- |
| `src/main/java/com/heilos/travel/TravelApplication.java` | Spring Boot 启动、依赖注入、连接池 |
| `AgentController.java` | AgentArts HTTP 入口、健康检查、请求体限制 |
| `TravelAgent.java`、`Prompts.java` | 三条业务路径、Spring AI ChatClient、文旅提示词 |
| `ExternalServices.java` | MobileNet 与 Koo Search HTTP 接口 |
| `CompatibleChatModel.java`、`SseDecoder.java` | Spring AI ChatModel 适配、上游模型协议与分片解码 |
| `WorkflowEvents.java` | SSE 事件、心跳及请求级状态 |
| `ImageInput.java`、`Inputs.java`、`AgentSettings.java` | 图片处理、请求契约、环境配置 |
| `src/test/java/com/heilos/travel/` | Java 自动化回归测试 |

迁移决策见 [MIGRATION.md](MIGRATION.md)。原始资料保留原样。
