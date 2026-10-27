# 环境变量配置说明

按 2026-10-12 工程代码整理，2026-10-14 补充分类故障降级配置，2026-10-19 核对并补充必填项汇总。下表说明变量的作用、是否必须、未配置时的默认行为，以及配置位置；不包含真实密钥。可复制的完整模板见 [.env.example](../.env.example)。

## 必填且无可用默认值的变量

完整支持真实知识库检索和图片问答时，需要配置以下 4 项；其中知识库 Token 可由文件提供。这些变量在代码中默认为空字符串，没有可直接使用的默认值。

| 环境变量 | 作用 | 必填条件 |
| --- | --- | --- |
| `RAGFLOW_URL` | 完整检索地址，包含 `/api/v1/retrieval`；按网络选择私网或公网 | `RAGFLOW_ENABLED=true` 且请求进入知识检索分支时 |
| `RAGFLOW_DATASET_ID` | 目标知识库 ID | `RAGFLOW_ENABLED=true` 且请求进入知识检索分支时 |
| `RAGFLOW_API_TOKEN` | 知识库访问凭据 | 执行真实检索且未配置 `RAGFLOW_API_TOKEN_FILE` 时 |
| `CLASSIFIER_API_KEY` | 图片分类服务密钥 | 请求包含图片时；图片分支始终先调用分类器 |

按场景配置：纯文本真实检索需要前三项；完整图片问答还需要分类器密钥，因为高置信度图片会进入检索。`RAGFLOW_ENABLED=false` 时，前三项可以不配置；纯文本请求也不需要分类器密钥，图片请求仍需要。仅启动服务、调用 `/ping` 或 `query_capabilities` 不需要这些业务凭据；缺失配置会在执行对应业务分支时返回 E5004。

需要区分的可选项与替代方式：

- `RAGFLOW_API_TOKEN_FILE` 可替代 `RAGFLOW_API_TOKEN`，配置后优先读取文件；文件不可读或内容无效时不会回退到环境 Token。
- `RAGFLOW_RERANK_ID` 没有默认值，但不是基础检索必填项；未配置时不发送重排参数，即使 `RAGFLOW_USE_RERANK=true` 也不会启用重排。
- `CLASSIFIER_URL` 已有默认值 `http://1.95.152.66:16000/mobilenetv3large`，可以不重复配置。
- 模型地址、名称和密钥由请求中的 `inputs.model_config` 提供，不属于运行时环境变量。

**`.env.example` 中填写的知识库地址、ID 和重排模型只是配置示例，不是代码默认值。** 需通过本地启动脚本加载 `.env`，或在 AgentArts 运行时显式配置；它们不会随镜像自动生效。公网与私网地址见下文[地址切换](#公网与私网地址切换)。

## 配置位置与生效方式

| 使用场景 | 配置位置 | 生效方式 |
| --- | --- | --- |
| AgentArts | 目标运行时的环境变量 | 保存并发布/切换到使用该配置的版本，用新的 Session ID 验证 |
| 本地日志脚本 | 工程根目录 `.env`，或 `TRAVEL_LOG_ENV_FILE` 指定文件 | `./scripts/verify_logs.sh start` 加载，修改后重启 |
| 直接启动 JAR | 启动进程的环境变量 | 在同一终端加载 `.env` 后启动，修改后重启 |
| 本地 Docker | `docker run --env-file .env ...` 或逐项 `-e` | 修改后用新环境变量重新创建容器 |

应用不会自动读取 `.env`，该文件也不进入 Git 或镜像。模板中填写的地址、知识库 ID 和重排模型不会自动成为云端配置。修改已有环境变量通常无需重建镜像，但必须让新进程读取配置；仅更新已配置的 Token 文件内容无需重启，每次检索都会重新读取。

直接启动 JAR 的示例：

```bash
set -a
source .env
set +a
java -jar target/travel-agent.jar
```

“必填”指对应业务分支执行时需要，并不表示缺少它就无法启动应用。`/ping` 和 `query_capabilities` 不依赖分类器、知识库或模型凭据；健康检查成功不代表真实问答配置完整。数值超范围、布尔值不合法、非空 URL 格式错误等配置可能使应用启动失败。

## 当前部署需要填写的值

为支持完整的文本和图片问答，在 AgentArts 逐项添加以下值；密钥占位符须替换为实际值：

```dotenv
# 文本和高置信度图片检索必需
RAGFLOW_URL=http://172.16.0.154:9380/api/v1/retrieval
RAGFLOW_DATASET_ID=e611c8e0c45111f19cb7ff060c22c24e
RAGFLOW_API_TOKEN=替换为实际RAGFlowToken

# 图片请求必需
CLASSIFIER_API_KEY=替换为实际图片分类器密钥

# 以下为当前接入方案使用的可选配置
RAGFLOW_RERANK_ID=bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible
RAGFLOW_USE_RERANK=true
RAGFLOW_KNN_TOP_K=64
RAGFLOW_KNN_NUM_CANDIDATES=128
RAGFLOW_RERANK_CANDIDATES_COUNT=32
CLASSIFIER_URL=http://1.95.152.66:16000/mobilenetv3large
```

RAGFlow Token 填原始值，不加 `Bearer ` 前缀，代码会添加请求头。分类器密钥与 RAGFlow Token 分别用于不同服务，平台 `E1-` 加密值不能直接作为这些凭据。模型凭据由每次请求的 `model_config.auth_token` 提供，不在以上环境变量中。

上例使用私网地址，也可将 `RAGFLOW_URL` 改为公网完整 URL `http://1.95.220.19:9380/api/v1/retrieval`。运行时需能访问所选地址，变量填写正确不等于网络已连通，见 [知识库网络说明](agentarts-knowledge-network.md)。

## RAGFlow 知识检索

默认情况下，纯文本请求，以及图片分类名称非空且置信度达到阈值的请求，会执行检索。其余图片走视觉模型，不执行检索，但仍先调用分类器。下表检索地址、知识库 ID 和 Token 的必填要求适用于 `RAGFLOW_ENABLED=true`。

| 环境变量 | 是否必须 | 未配置时 | 作用与要求 |
| --- | --- | --- | --- |
| `RAGFLOW_ENABLED` | 选填 | `true` | 是否调用真实 RAGFlow；false 时立即返回空检索结果、继续模型问答，不发送检索请求、不读取 Token 文件，不增加模拟等待 |
| `RAGFLOW_URL` | 检索必填 | 空，检索返回 E5004 | 完整 HTTP(S) 检索 URL，支持私网或公网地址；需包含 `/api/v1/retrieval`，不能只填 IP、主机或 `/api/v1`。代码直接使用此值，不自动追加路径 |
| `RAGFLOW_DATASET_ID` | 检索必填 | 空，检索返回 E5004 | 作为 `dataset_ids` 数组中的唯一知识库 ID；1～64 位字母、数字、下划线或连字符 |
| `RAGFLOW_API_TOKEN` | 条件必填 | 空 | 没有使用 Token 文件时必填；作为 `Authorization: Bearer <Token>` 发送。与 AgentArts 入口密钥、模型密钥不同 |
| `RAGFLOW_API_TOKEN_FILE` | 选填 | 不读文件，使用环境 Token | 运行时内可读的 UTF-8 Token 文件路径，优先于 `RAGFLOW_API_TOKEN`；每次检索重读，文件不存在、不可读或内容无效时返回 E5004，不回退到环境 Token |
| `RAGFLOW_RERANK_ID` | 启用重排时填写 | 空，不发送 `rerank_id` | 重排模型标识。接入模板填写 `bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible`；仅当 ID 非空且重排开关为 true 时发送 |
| `RAGFLOW_USE_RERANK` | 选填 | `true` | 重排开关；false 时不发送 `rerank_id` 和 `rerank_candidates_count`，可保留已配置的模型 ID 供恢复使用；不是发给 RAGFlow 的字段 |
| `RAGFLOW_KNN_TOP_K` | 选填 | `64` | kNN 向量召回数量，发送为 `knn_top_k`；正整数，显式空值或字面量 `null` 表示省略 |
| `RAGFLOW_KNN_NUM_CANDIDATES` | 选填 | `128` | kNN 搜索候选池大小，发送为 `knn_num_candidates`；正整数，显式空值或 `null` 表示省略 |
| `RAGFLOW_RERANK_CANDIDATES_COUNT` | 选填 | `32` | 送重排模型的候选数，发送为 `rerank_candidates_count`；仅重排开关为 true 且 ID 非空时发送，显式空值或 `null` 表示省略 |
| `RAGFLOW_TOP_K` | 选填 | `3` | 整数 1～50；作为请求中的 `page_size`，同时限制客户端最多保留的片段数；不是 `knn_top_k` |
| `RAGFLOW_SIMILARITY_THRESHOLD` | 选填 | `0.2` | 范围 0～1；作为请求中的 `similarity_threshold`，交由 RAGFlow 处理 |
| `RAGFLOW_MIN_SIMILARITY` | 选填 | `0.3` | 范围 0～1；收到响应后，客户端过滤 similarity 小于此值的片段，等于阈值可保留；不作为请求字段发送 |
| `RAGFLOW_TIMEOUT` | 选填 | `10` 秒 | 0.000001～300 秒；整个检索操作的总时限，包含 Token 文件读取、所有请求与重试等待 |
| `RAGFLOW_RETRIES` | 选填 | `2` 次 | 整数 0～2；连接异常或 HTTP 408/429/5xx 的额外重试次数；0 表示不重试，2 表示最多请求 3 次，仍受总时限约束 |

例如 `RAGFLOW_TOP_K=3`、服务端阈值 0.2、客户端阈值 0.3 时，向服务端请求一页 3 条结果，再剔除空正文和低于 0.3 的片段，因此最终可能少于 3 条，甚至为 0。空结果会继续模型问答；检索失败不会当作空结果继续。

### 公网与私网地址切换

沿用一个环境变量 `RAGFLOW_URL`，按运行时网络选择完整地址：

| 访问方式 | `RAGFLOW_URL` |
| --- | --- |
| 私网 | `http://172.16.0.154:9380/api/v1/retrieval` |
| 公网 | `http://1.95.220.19:9380/api/v1/retrieval` |

公网访问配置示例：

```dotenv
RAGFLOW_ENABLED=true
RAGFLOW_URL=http://1.95.220.19:9380/api/v1/retrieval
```

继续配置该知识库的 `RAGFLOW_DATASET_ID` 和有效 `RAGFLOW_API_TOKEN`（或 Token 文件）。本地修改 `.env` 后重启；AgentArts 修改运行时环境变量后发布/切换到对应版本。已发布镜像支持此配置，无需重新构建。应用只请求选定地址，不自动在公网与私网之间回退；若 `RAGFLOW_ENABLED=false`，仍会跳过检索。

公网地址由用户于 2026-10-12 提供，本次未发起真实检索，不代表已验证其网络连通性和鉴权。

### 跳过真实检索进行测试

在本地 `.env` 或 AgentArts 运行时环境变量中设置：

```dotenv
RAGFLOW_ENABLED=false
```

此时无需填写 RAGFlow 地址、知识库 ID、Token 或 Token 文件。应用会沿用空结果提示词；高置信度图片仍经分类器后调用文本模型，低置信度图片仍走视觉模型。模型配置和图片分类器凭据按原有规则提供。已填写配置的格式校验仍在启动时执行，例如非法 URL、数值或布尔值仍会报配置错误。

启动日志 `ragflow_config` 显示 `enabled=false`。进入检索阶段的请求输出 `ragflow_skipped`，其中 `status=skipped`、`attempts=0`；不会输出真实检索的 `ragflow_result` 或 `ragflow_content`。耗时和 TTFT 日志的 `search_mode=disabled`，保留的 search_text/search_image 阶段仅表示跳过处理及提示词准备耗时。

改为 `true` 或删除该变量可恢复真实检索，需要有效的 RAGFlow 配置。开关修改后重启本地进程，或发布/切换 AgentArts 运行时版本；首次使用此开关需要部署包含本次代码的新镜像。

### 候选数、返回条数与回滚

三个候选数变量在本工程接受 1～2147483647 的整数，服务端限制与实际语料规模仍需在部署环境核对。这些变量均支持空字符串或字面量 `null`（忽略大小写和首尾空白），对应请求字段会被省略，而不是发送 JSON null。其他数值变量沿用各自的默认值和校验规则。

| 请求字段 | 环境变量 | 本工程默认值 | 新接入文档给出的服务端默认值 |
| --- | --- | --- | --- |
| `knn_top_k` | `RAGFLOW_KNN_TOP_K` | 64 | 1024 |
| `knn_num_candidates` | `RAGFLOW_KNN_NUM_CANDIDATES` | 128 | 2048 |
| `rerank_candidates_count` | `RAGFLOW_RERANK_CANDIDATES_COUNT` | 32（启用重排时发送） | 100 |
| `page_size` | `RAGFLOW_TOP_K` | 3 | 30 |

上表的服务端默认值来自用户新提供的接入说明，尚未通过本项目访问目标服务核验；适用于该文档所述部署，不作为所有 RAGFlow 版本的通用默认值。

恢复服务端默认候选数时，显式设置：

```dotenv
RAGFLOW_KNN_TOP_K=null
RAGFLOW_KNN_NUM_CANDIDATES=null
RAGFLOW_RERANK_CANDIDATES_COUNT=null
```

删除这三个变量会恢复本工程的 `64/128/32` 默认值。关闭重排时设 `RAGFLOW_USE_RERANK=false`；重新设为 true 且保留有效 ID 即可恢复，环境变量修改后均需重启/发布运行时配置。要恢复旧客户端的请求字段，可省略三个候选数，并保持原有 `RAGFLOW_RERANK_ID` 与默认的 true 开关。

实际检索请求包含 `dataset_ids`、`question`、`page=1`、`page_size`、`similarity_threshold`，并按上述规则加入候选数及重排字段。启动日志 `ragflow_config` 显示实际启用状态 `rerank_enabled` 和候选数；日志中的候选数为 null 表示不发送。实现见 [RagFlowClient.java](../src/main/java/com/travel/agent/RagFlowClient.java)。

## 图片分类器

| 环境变量 | 是否必须 | 未配置时 | 作用与要求 |
| --- | --- | --- | --- |
| `CLASSIFIER_API_KEY` | 图片请求必填 | 空，分类返回 E5004 | 原始分类器密钥，放入 `X-API-Key` 请求头；纯文本不使用 |
| `CLASSIFIER_URL` | 选填 | `http://1.95.152.66:16000/mobilenetv3large` | 分类接口完整 URL；当前部署可沿用默认值 |
| `CLASSIFIER_THRESHOLD` | 选填 | `0.7` | 范围 0～1；分类结果合法且置信度 ≥ 此值走知识检索与文本模型；合法低置信度结果走视觉模型，非法字段按服务失败处理 |
| `CLASSIFIER_TIMEOUT` | 选填 | `30` 秒 | 0.000001～300 秒；分类请求总超时 |
| `CLASSIFIER_FALLBACK_ON_TRANSIENT_ERROR` | 选填 | `false` | 接受 true/false；true 时仅分类调用超时、连接失败、HTTP 5xx 转视觉模型；false 时这些故障直接失败。鉴权、配置、TLS/DNS、业务及响应格式错误不降级 |

开启暂时性分类故障降级：

```dotenv
CLASSIFIER_FALLBACK_ON_TRANSIENT_ERROR=true
```

降级不重试分类、不调用知识库，需要请求中的 `model_config` 提供 `text_vision` 模型配置；缺少时返回模型配置错误。所有分类故障记录 `classifier_failed`，其中 `fallback_to_vision` 表示是否选择降级，`alert=true` 可用于配置告警规则。合法低置信度分流不属于故障，不受此开关影响。详见 [分类失败与降级](logging.md#分类失败与降级)。

首次使用需要部署包含此开关实现的镜像；之后修改变量无需重建镜像，但需重启进程或发布/切换 AgentArts 运行时配置使其生效。

## 模型调用与输入限制

| 环境变量 | 是否必须 | 未配置时 | 作用与要求 |
| --- | --- | --- | --- |
| `MODEL_TIMEOUT` | 选填 | `60` 秒 | 0.000001～600 秒；模型整次调用的总时限，包括等待首片及后续流式输出，不因持续收到片段而重置 |
| `MODEL_ALLOWED_HOSTS` | 选填 | 空，不启用应用层主机白名单 | 逗号分隔的模型 endpoint 主机名，忽略大小写；只填主机，不带协议、端口和路径。例如 `api.modelarts-maas.com` |
| `MAX_REQUEST_BYTES` | 选填 | `16777216` 字节，16 MiB | 入站 JSON 请求体大小上限；超出返回 HTTP 413 |
| `MAX_IMAGE_BYTES` | 选填 | `10485760` 字节，10 MiB | Base64 图片解码后的大小上限，并提前检查编码长度；不会据此下载并测量 URL 图片大小 |
| `MAX_CONTEXT_CHARS` | 选填 | `16000` 字符 | 放入模型提示词的知识片段上下文上限，不是整个模型请求体的大小限制 |

以上 3 个 `MAX_*` 变量要求整数，范围 1～2147483647。模型地址、`model_name`、`provider`、`temperature`、`auth_token`、`chat_template_kwargs`、`thinking` 均由请求中的 `inputs.model_config` 传入，不是运行时环境变量；模型请求固定携带 `stream=true`。

## 日志与 TTFT

日志行首已在 Logback 中固定为北京时间，格式为 `yyyy-MM-dd HH:mm:ss.SSS`（如 `2026-10-12 11:06:37.956`），无需额外配置 `TZ` 或 JVM 时区参数。使用该格式需要部署包含这次日志配置修改的镜像。

| 环境变量 | 是否必须 | 未配置时 | 作用与要求 |
| --- | --- | --- | --- |
| `RAGFLOW_LOG_CONTENT` | 选填 | `false` | `true` 时记录检索问题与原始 chunks 正文；默认只记录摘要 |
| `MODEL_LOG_REQUEST` | 选填 | `false` | `true` 时记录最终模型请求体，包含知识上下文、系统提示和用户问题；默认只记录模型调用摘要 |
| `DIAGNOSTIC_LOG_MAX_CHARS` | 选填 | `16000` 字符 | 整数 256～65536；诊断 JSON 超长时输出带 `truncated=true` 标记的预览，不改变实际模型请求 |

两个开关接受 `true`/`false`。日志对已知鉴权字段、凭据值和图片数据做脱敏，正文开关只在需要排查内容时启用。配置变更后重启进程。

`workflow_timing`、`workflow_ttft` 和检索/模型调用摘要默认记录，不依赖上述两个正文开关，也没有单独的 TTFT 环境变量。TTFT 从工作流开始计至首个非空正文/思考片段，需部署包含 TTFT 实现的镜像。AgentArts 的“日志记录”及 LTS 采集属于平台配置，并非这里的环境变量。查看方式和完整字段见 [日志说明](logging.md)。

## 监听、心跳与工作流标识

| 环境变量 | 是否必须 | 未配置时 | 作用与要求 |
| --- | --- | --- | --- |
| `PORT` | 选填 | JAR/镜像为 `8080` | 应用监听端口；AgentArts 运行时端口与健康检查应配套。本地 `verify_logs.sh start` 会按命令参数或 `TRAVEL_LOG_PORT` 覆盖它，默认 8081 |
| `HEARTBEAT_SECONDS` | 选填 | `15` 秒 | 0.000001～60 秒；SSE 无业务事件时发送 keep-alive 注释，心跳不是模型 token |
| `WORKFLOW_ID` | 选填 | `wenlv-highcode` | SSE 中的工作流标识，不是 AgentArts runtime ID，也不是每次请求的 execution_id |
| `WORKFLOW_NAME` | 选填 | `文旅逛展` | SSE 中展示的工作流名称 |
| `JAVA_TOOL_OPTIONS` | 选填 | Docker 镜像设置 `-XX:MaxRAMPercentage=70.0`；直接启动由进程环境决定 | JVM 启动参数；可调整 JVM 行为，非知识检索配置 |

应用默认监听 `0.0.0.0`，健康接口为 `/ping`。以上业务默认值来源于 [AgentSettings.java](../src/main/java/com/travel/agent/AgentSettings.java)，监听配置见 [application.properties](../src/main/resources/application.properties)，镜像默认值见 [Dockerfile](../Dockerfile)。

## 仅在本地构建或调用工具中使用的变量

这些变量供开发电脑/调用工具使用，不需要配置到 AgentArts 文旅容器中。

| 变量 | 是否必须 | 默认行为与作用 |
| --- | --- | --- |
| `JAVA_HOME` | 选填，但构建/运行需要 JDK 21 | 日志脚本优先使用指定 JDK，否则尝试查找本机 JDK 21 |
| `MAVEN_USER_HOME` | 选填 | Maven Wrapper 使用的用户/分发缓存位置，未指定沿用 Wrapper 默认位置 |
| `MAVEN_REPO_LOCAL` | 选填 | `verify_logs.sh test/build` 将其转为 `-Dmaven.repo.local`；直接执行 `./mvnw` 时应显式传该 Maven 参数 |
| `TRAVEL_LOG_ENV_FILE` | 选填 | 日志脚本 start 加载的配置文件，默认工程根目录 `.env` |
| `TRAVEL_LOG_FILE` | 选填 | 日志脚本保存/筛选的文件，默认 `target/local-logs/application.log` |
| `TRAVEL_LOG_PORT` | 选填 | 日志脚本的默认服务端口为 8081，命令行端口参数优先；应在运行脚本前设置 |
| `AGENTARTS_RUNTIME_URL` | IAM 脚本条件必填 | 完整运行时 HTTPS 调用 URL；可用 `--url` 替代 |
| `AGENTARTS_REGION` | 选填 | IAM 签名区域，默认 `cn-southwest-2`；可用 `--region` 覆盖，应与实际区域一致 |
| `HUAWEICLOUD_SDK_AK` | IAM 脚本必填 | 调用方长期 Access Key，用于请求签名 |
| `HUAWEICLOUD_SDK_SK` | IAM 脚本必填 | 调用方长期 Secret Key，用于请求签名 |
| `APIG_SDK_PATH` | 条件选填 | 官方签名 SDK 目录；也可用 `--sdk-dir`，或让 `apig_sdk` 已能被 Python 导入 |

IAM 脚本不支持带 `HUAWEICLOUD_SDK_SECURITY_TOKEN` 的临时凭据，设置后会报错；详细步骤见 [IAM 验证](verification.md#iam-运行时验证)。部署文档 curl 示例中的 `AGENTARTS_API_KEY`、`AGENTARTS_GATEWAY`、`RUNTIME_NAME`、`SESSION_ID` 是调用终端使用的变量，也不是容器业务配置。
