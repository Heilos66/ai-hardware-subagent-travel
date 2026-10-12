# 文旅 Agent

Java 21 + Spring Boot 3.5.15 + Spring AI 1.0.9（WebFlux），部署到 AgentArts 高代码运行时，不依赖巫山框架。

保留 MobileNet 分类与三条路径：纯文本进入检索和文本模型；图片分类名称非空且置信度 ≥ 0.7 时进入检索和文本模型；其余图片进入视觉模型。分类 HTTP 失败直接结束流程。知识检索统一使用 RAGFlow，接入文档中的 travel_kb，真实私网连通性仍待云端验收。

## 目录

```text
.
├── src/main/          # 应用代码、配置和日志
├── src/test/          # 回归测试
├── scripts/           # IO、IAM 调用、日志验证脚本及客户端测试
├── docs/
│   ├── deployment.md  # 镜像、SWR、AgentArts 部署
│   ├── verification.md # 验证步骤和已验证基线
│   ├── logging.md     # 日志打点、AgentArts/LTS 保存与查看
│   └── ragflow-travel-agent-integration.md # 当前 RAGFlow 接入与参考文档
├── references/        # 本地 IO 示例、原始工作流 JSONL（Git 忽略）
├── .mvn/              # Maven Wrapper 配置
├── .env.example       # 环境变量模板
├── pom.xml
├── mvnw / mvnw.cmd
└── Dockerfile
```

`.env` 保存本机配置，`references/io_spec.md` 用于真实请求测试；两者与原始 `workflow_文旅逛展_1_20260924171300.jsonl` 均保留在本机，不进入 Git 或镜像。`target/` 为可重新生成的构建和日志目录。

## 本地启动

需要 JDK 21；可通过 `JAVA_HOME` 指定。项目包含 Maven Wrapper，首次构建需要下载 Maven 和依赖，Windows 使用 `mvnw.cmd`。

```bash
test -f .env || cp .env.example .env
# 编辑 .env 中的分类器及 RAGFlow 配置
./scripts/verify_logs.sh build
./scripts/verify_logs.sh start
```

脚本优先使用 `JAVA_HOME`，也会查找本机 JDK 21。start 读取 `.env`，默认监听 8081，使用其中的真实 RAGFlow 配置，日志追加到 `target/local-logs/application.log`。源码修改后重新 build 并重启；运行时环境变量修改也需要重启。

在另一个终端执行：

```bash
./scripts/verify_logs.sh capabilities  # 无需外部服务
./scripts/verify_logs.sh failure       # 预期业务错误
./scripts/verify_logs.sh request       # 使用本地 IO 示例，按分支调用分类器/RAGFlow/模型
./scripts/verify_logs.sh logs          # 查看耗时与检索/模型诊断
./scripts/verify_logs.sh logs 实际execution_id
```

可追加端口，例如 `start 8082`、`request 8082 /实际路径/io_spec.md`。`python3 scripts/test_io_spec.py --check` 只校验 IO 文件，不发送请求。完整参数见 `./scripts/verify_logs.sh help`。

也可显式加载 `.env` 后直接运行 JAR，两种启动方式均使用 RAGFlow：

```bash
set -a
source .env
set +a
java -jar target/travel-agent.jar
```

直接运行 JAR 默认监听 8080，支持 `PORT` 环境变量。停止服务按 Ctrl+C。单次请求中的模型配置每次读取，无需重启。

## 配置

完整环境变量及默认值见 [.env.example](.env.example)。应用不自动加载环境文件；本地脚本负责加载，AgentArts 使用运行时环境变量。

| 配置 | 用途 |
| --- | --- |
| `CLASSIFIER_URL` | 默认 `http://1.95.152.66:16000/mobilenetv3large` |
| `CLASSIFIER_API_KEY` | 原始可用密钥，作为 `X-API-Key` 发送；平台 `E1-` 加密值不可直接使用 |
| `CLASSIFIER_THRESHOLD` | 默认 0.7 |
| `RAGFLOW_URL/DATASET_ID/API_TOKEN` | 完整检索 URL、知识库 ID、Bearer Token；见 [RAGFlow 接入](docs/ragflow-travel-agent-integration.md#本工程接入与验证) |
| `RAGFLOW_API_TOKEN_FILE` | 可选挂载文件，优先于 Token 环境变量，每次查询重读 |
| `RAGFLOW_TOP_K/SIMILARITY_THRESHOLD/MIN_SIMILARITY` | 默认取 3 条，服务端阈值 0.2，客户端过滤低于 0.3 的片段 |
| `RAGFLOW_TIMEOUT/RETRIES` | 整次检索默认 10 秒，连接异常或 HTTP 408/429/5xx 最多重试 2 次 |
| `RAGFLOW_LOG_CONTENT/MODEL_LOG_REQUEST` | 默认 false；按需记录检索片段正文 / 最终模型请求体 |
| `DIAGNOSTIC_LOG_MAX_CHARS` | 诊断 JSON 超过此字符数时改为截断预览；默认 16000，范围 256～65536 |
| `CLASSIFIER_TIMEOUT/MODEL_TIMEOUT` | 分类器 / 模型整次调用超时，默认 30/60 秒 |
| `MODEL_ALLOWED_HOSTS` | 可选的模型目标主机限制，逗号分隔 |
| `MAX_REQUEST_BYTES/MAX_IMAGE_BYTES/MAX_CONTEXT_CHARS` | 默认 16MiB / 10MiB / 16000 字符 |
| `HEARTBEAT_SECONDS` | SSE 心跳间隔，默认 15 秒 |

模型地址、名称、凭据及思考选项由请求中的 model_config 提供，不使用共享的固定模型配置。

## 请求与流式响应

健康接口：`GET /ping`。问答接口：`POST /invocations`，兼容 `/stream` 和 `/invocations/stream`。请求必须为 `application/json`，保持 `inputs` 外层：

```json
{
  "inputs": {
    "operation": "chat_completions",
    "query": "请介绍这件展品",
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
        "thinking": {"type": "enabled"},
        "auth_token": "REPLACE_AT_CALL_TIME"
      }
    ]
  }
}
```

- 纯文本省略 image_url；图片也支持 image_base64 数组，两者互斥，只处理第一张。支持 JPG/JPEG/PNG；有图片时 query 可为空，否则必填，最多 1000 字符。
- operation 缺省、null 或空字符串按 chat_completions 处理。query_capabilities 返回三项能力；create_response/fetch_response 不受支持。
- openai-compatible 直接调用完整 endpoint，使用 Bearer Token；其他 provider 保留原 temp_token/action/model_request 代理协议及 /invocations/stream 路径。
- 所有模型请求固定 stream=true。chat_template_kwargs 和 thinking 支持对象或对象的 JSON 字符串，解析后按原字段名透传；未传/null 时省略，空对象保留，两项均传时均保留。
- 不维护模型会话历史，enable_history=false。最新系统提示词见 [Prompts.java](src/main/java/com/heilos/travel/Prompts.java)。

SSE 顺序为 workflow_started、node_started、message、workflow_finished、end。message.data.text 与 message.data.reasoning_content 都是增量，各自拼接；最终 responseContent 是 JSON 字符串，成功解析为 `{"code":"0","message":"正文"}`，只包含正文。能力查询的 message.text 和最终结果为能力 JSON 字符串，字段为 chat_completions/create_response/fetch_response。

上游必须有 [DONE] 或有效 finish_reason；提前断流、仅有思考无正文均失败。兼容上游普通 JSON 单片回退；单个 SSE 事件上限 1MiB，普通 JSON 上限 16MiB。模型超时不因持续分片而重置，断连和超时会取消上游。

业务错误在最终 responseContent 返回：400/401/500 为输入或分类错误，E5002 为检索失败，E5003 为模型失败，E5004 为配置或内部错误。外层 status.code 成功为 1、失败为 -1；客户端须读取内层业务 code。非法 JSON、错误 Content-Type、超大请求直接返回 HTTP 400/415/413。

## 阶段耗时日志

每个实际阶段结束后输出 workflow_timing，包含 execution_id、stage、status、elapsed_ms、search_mode。按 execution_id 关联 SSE 与日志；total 为服务端工作流总耗时。所有耗时记录为 INFO，失败/取消分别用 status=failed/cancelled 表示。

模型收到首个非空正文或思考片段时，立即输出一次 `workflow_ttft`。`ttft_ms` 从工作流开始计时，同一行包含校验、分类、检索和 `model_to_first_token_ms`，便于核对 `stages_sum_ms + overhead_ms ≈ ttft_ms`。原来的 `stage=model` 仍表示模型阶段完整耗时，不能用它代替首片等待耗时。TTFT 默认开启、无需正文日志开关，日志脚本已支持筛选；字段和示例见 [TTFT 说明](docs/logging.md#工作流-ttft)。

workflow_diagnostic 与耗时日志共用 execution_id，默认记录检索成功/失败、返回和保留数量、文档名及相似度，不记录正文。设置 `RAGFLOW_LOG_CONTENT=true` 可查看原始检索片段，`MODEL_LOG_REQUEST=true` 可查看包含最终知识上下文的模型请求体。日志会隐藏已知凭据字段/值和图片数据，限制正文长度；详见 [诊断日志](docs/logging.md#检索与模型请求诊断日志)。环境变量变更后重启服务；AgentArts 需发布配置并调用对应版本。

耗时和诊断各自异步输出，队列满时丢弃以避免日志写入阻塞业务线程。诊断序列化和脱敏仍有处理开销，正文开关默认关闭。

日志阶段和本地验证见 [验证指南](docs/verification.md)，云端保存和查看见 [日志说明](docs/logging.md)。本地日志文件不会随镜像上传；云端需独立开启日志上报并验证采集。

已部署 IAM 运行时可使用 `python3 scripts/test_agentarts_iam.py` 验证能力查询，追加 `--spec references/io_spec.md` 验证真实业务及 SSE，使用 `--expect-auth-rejection` 测试无效签名拒绝。先按 [IAM 验证步骤](docs/verification.md#iam-运行时验证) 配置官方签名 SDK、调用方 AK/SK 和完整运行时 URL；不需要修改 Java 服务或重新构建镜像。

## 测试与部署

```bash
./mvnw verify                       # 全部 189 项回归
./scripts/verify_logs.sh test       # 耗时与诊断日志专项测试
```

测试使用本机模拟服务，不消耗真实模型额度。Spring AI 的 ChatClient 通过自定义 CompatibleChatModel 处理现有两类模型协议和增量语义；MobileNet、RAGFlow、阶段日志及取消传播均有回归覆盖。RAGFlow 的空结果正常进入模型，HTTP/业务/格式错误返回 E5002，配置缺失返回 E5004；不把检索故障当作无资料。无需外部服务时可执行能力查询、输入错误验证或自动化测试。

[部署指南](docs/deployment.md) 包含 Docker V2 镜像构建、asr-test2 下的 SWR 推送和 AgentArts 运行时配置；[验证指南](docs/verification.md) 保留实际验证摘要及仍待验收的云端链路。
