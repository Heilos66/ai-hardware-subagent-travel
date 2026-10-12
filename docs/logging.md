# AgentArts 高代码运行日志调研

查询日期：2026-10-09。资料来自华为云官方文档；本次未登录租户核验或修改云端配置。

## AgentArts 如何接入与保存

高代码托管运行时开启“日志记录”后，运行日志由平台上报到云日志服务 LTS。AgentArts 提供查看入口，留存策略在 LTS 管理；前提是已开通并授权 LTS。观测页面展示本身不收费，日志上报、存储等按 LTS 计费规则执行。[来源：上报智能体运行时数据，2026-09-28 更新](https://support.huaweicloud.com/ops-agentarts/agentarts_14_0006.html)

运行时创建 API 中的 `observability.logs.enabled` 默认是 `false`，不能直接沿用低代码智能体“默认上报”的说明。日志组 ID 与日志流 ID 必须同时指定或同时省略；`project_id` 表示日志流所在项目。[来源：CreateCoreRuntime，CoreRunLogsConfig](https://support.huaweicloud.com/api-agentarts/CreateCoreRuntime.html)

需要核实保存到哪个日志流时，查询目标运行时的实际版本：`GET /v1/core/runtimes/{runtime_id}`，可用 `version` 指定版本。响应中的 `version_detail.observability.logs` 包含 `enabled`、`group_id`、`stream_id` 和 `project_id`，可据此定位 LTS，不能根据 SWR 组织名 `asr-test2` 猜日志组名称。[来源：ShowCoreRuntime](https://support.huaweicloud.com/api-agentarts/ShowCoreRuntime.html)

## 在 AgentArts 开启和查看

1. 在“授权管理 → 云服务”确认 LTS 已开通且已授权；只有服务开通状态不代表授权已完成。[来源：管理云服务授权信息](https://support.huaweicloud.com/resources-members-agentarts/agentarts_15_0009.html)
2. 按新版导航进入“托管与运行 → 运行时”，创建/配置运行时时启用“日志记录”。已有运行时可以从详情中的“日志”页签进入，未启用时使用“去启用”。[新版配置文档](https://support.huaweicloud.com/ops-agentarts/agentarts_14_0006.html)、[已有运行时的日志页签](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_032.html)
3. 开启后通过 API 触发一次真实运行时调用，再进入“观测与优化 → 观测 → 智能体运行分析 → 智能体运行时”，点目标名称/ID 查看日志，并选择覆盖本次调用的时间范围。[来源：查看智能体运行时数据信息，2026-09-28 更新](https://support.huaweicloud.com/ops-agentarts/agentarts_14_0015.html)

2026-07/08 的文档和部分控制台可能仍显示“部署运行 → 智能体运行时”“运营运维 → 观测”。本次按 2026-09-28 的新页面名称整理，旧菜单对应同一用途。运行时日志和网关日志有独立入口；查应用的分类、检索、模型耗时，优先选运行时日志。[新入口](https://support.huaweicloud.com/ops-agentarts/agentarts_14_0006.html)、[旧运行时入口](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_032.html)、[网关日志入口](https://support.huaweicloud.com/highcode-agentarts/agentarts_10_085.html)

## 当前文旅 Java 工程如何对应

应用日志行首固定使用北京时间（`Asia/Shanghai`），格式为 `yyyy-MM-dd HH:mm:ss.SSS`，例如 `2026-10-12 11:06:37.956`，不显示 `T`、`Z` 或时区后缀。时区在 Logback 格式中显式指定，不依赖宿主机/JVM 默认时区或 `TZ` 环境变量。该设置只改变日志时间的显示，`elapsed_ms`、`classify_ms` 和 `ttft_ms` 仍使用单调时钟计算；SSE 的毫秒时间戳保持原有含义。LTS 单独展示的事件/采集时间属于平台字段。

应用使用 `SLF4J → Logback 异步队列 → CONSOLE`，输出 `workflow_timing`、`workflow_ttft` 和 `workflow_diagnostic`，没有配置文件 Appender 或在请求线程里调用 LTS API。阶段耗时与 TTFT 共用计时队列，诊断日志使用独立队列；队列满时可以丢弃日志，以保持业务不被日志写入阻塞。[代码：logback-spring.xml](../src/main/resources/logback-spring.xml)、[代码：WorkflowTiming.java](../src/main/java/com/heilos/travel/WorkflowTiming.java)、[代码：WorkflowDiagnostics.java](../src/main/java/com/heilos/travel/WorkflowDiagnostics.java)

建议先使用平台内置日志上报完成验收，暂不为此引入额外 SDK。**文档明确确认运行时日志会进入 LTS，但本次没有云端样本证明当前镜像的 stdout 已被收录，也没有找到任意自定义日志文件均会自动采集的承诺。**保留现有控制台输出，并按以下过程核验平台采集结果；若只有平台日志而没有应用打点，再核对目标版本、采集范围和过滤规则。

| 本项目内容 | 保存/查看方式 |
| --- | --- |
| 本地脚本产生的 `target/local-logs/application.log` | 由 `verify_logs.sh` 的 `tee` 在本机写入；不在镜像中，也不会因推送 SWR 而上传 |
| 镜像中的应用日志 | 由 Logback 输出到容器控制台；启用平台日志记录后，需到对应 LTS 日志流验证采集 |
| SSE 中的 `execution_id` | 应用为每次工作流生成的 ID，用于关联打点；不等于平台的 runtime ID 或 Session ID |
| `elapsed_ms` | 本阶段或工作流的毫秒耗时；`total` 不包含客户端到平台的完整网络往返 |

上表依据：[本地验证脚本](../scripts/verify_logs.sh)、[镜像构建文件](../Dockerfile)、[日志实现](../src/main/java/com/heilos/travel/WorkflowTiming.java)、[SSE 与 ID 关联实现](../src/main/java/com/heilos/travel/WorkflowEvents.java)。

云端验证时先确认运行时使用含打点的镜像版本，然后发送一条业务请求，记下结束帧的 `execution_id`。在日志页面先搜索 `workflow_timing`，再按该请求 ID 检查阶段顺序：

| 请求分支 | 应看到的 stage 顺序 |
| --- | --- |
| 纯文本 | `validate → search_text → model → total` |
| 高置信度图片，且识别名称非空 | `validate → classify → search_image → model → total` |
| 低置信度图片 | `validate → classify → model → total` |

本项目的所有耗时记录都是 INFO 级别，成功/失败/取消由 `status=succeeded/failed/cancelled` 区分，筛选 ERROR 级别会漏掉这些记录。`search_mode` 表示配置的检索模式：默认 `ragflow`，设 `RAGFLOW_ENABLED=false` 时为 `disabled`，同样适用于 TTFT 日志。真实检索阶段记录读取 Token 文件、HTTP 请求、重试及提示词准备的耗时；disabled 时仅记录跳过处理及提示词准备，不增加模拟等待。一次请求应有一条 total；异步日志可能略晚于 SSE 结束到达。[本地实现和验证说明](../README.md#阶段耗时日志)

截至本次调研，运行时观测指南仍注明托管智能体的 Trace/Metric 查看功能未开放，而 API 结构里已经存在相应配置字段。因此不能仅凭开了日志开关或 API 中有字段，就认定会自动出现分类/检索/模型的链路图、P95 或 Token 图表；本项目当前提供的是日志打点，可在 LTS 另外分析。[运行时观测指南](https://support.huaweicloud.com/ops-agentarts/agentarts_14_0006.html)、[运行时 API 配置字段](https://support.huaweicloud.com/api-agentarts/ShowCoreRuntime.html)

## 工作流 TTFT

2026-10-11 新增。默认在 INFO 级别输出 `workflow_ttft`，不需要开启检索正文或模型请求体日志。以工作流订阅开始为起点，在模型适配器收到并解析出首个非空 `content` 或 `reasoning_content` 时记录，早于向调用方转发对应 SSE 消息。每次工作流最多一条，沿用同一个 `execution_id`。

这里的 TTFT 覆盖工作流内的校验、分类、检索、提示词准备及模型首片等待，不包含客户端到 AgentArts/应用的入站网络耗时，也不包含向客户端发送首片的网络耗时。接口无法观测模型内部单个 token 的生成时刻，因此使用首个有效响应片段的接收时刻。只有角色的空片段、心跳、usage 及结束标记不触发计时；思考先到时，`first_token_type=reasoning_content`。

| 字段 | 含义 |
| --- | --- |
| `ttft_ms` | 工作流开始到首个有效模型片段的毫秒数 |
| `validate_ms` | 参数校验阶段；与同请求的 validate 日志一致 |
| `classify_ms` | 分类阶段；未走该分支为 0 |
| `search_text_ms` / `search_image_ms` | 对应检索阶段，包含重试及提示词准备；未执行的分支为 0 |
| `model_to_first_token_ms` | model 阶段开始至首个有效片段，包含请求准备、发送和等待 |
| `stages_sum_ms` | 上面各阶段与 model_to_first_token_ms 的合计 |
| `overhead_ms` | TTFT 中未归入上述阶段的时间，例如工作流初始化及进入首个阶段前的开销 |
| `first_token_type` | content、reasoning_content 或 content_and_reasoning |
| `response_mode` | sse 或 json；JSON 回退要等完整响应接收解析后才能记录，不能代表真实流式首 token 延迟 |

公式为 `validate_ms + classify_ms + search_text_ms + search_image_ms + model_to_first_token_ms ≈ stages_sum_ms`，以及 `stages_sum_ms + overhead_ms ≈ ttft_ms`。统一使用单调时钟，阶段交接共享同一个计时点；字段保留三位小数，可能存在微小舍入误差。

以下为示意值，所有信息实际在同一行：

```text
workflow_ttft execution_id=example ttft_ms=1000.0 validate_ms=2.0 classify_ms=80.0 search_text_ms=0.0 search_image_ms=150.0 model_to_first_token_ms=765.0 stages_sum_ms=997.0 overhead_ms=3.0 first_token_type=reasoning_content response_mode=sse search_mode=ragflow
```

这条日志在模型还在继续输出时就产生。原有 `stage=model` 仍记录整个模型阶段，`stage=total` 仍记录完整工作流，不应将这些完整耗时再加进 TTFT。首片前失败/超时/取消以及能力查询不产生 TTFT；首片后失败/取消保留已产生的 TTFT，最终成败仍以阶段/total 日志和 SSE 结束帧为准。

本地执行 `./scripts/verify_logs.sh logs 实际execution_id` 会同时显示 TTFT、阶段耗时和诊断记录；云端按 `workflow_ttft` 与 execution_id 搜索。TTFT 不包含问题、知识正文或凭据，使用现有非阻塞异步日志队列，日志故障不会中断模型流。

## 检索与模型请求诊断日志

2026-10-10 新增。搜索 `workflow_diagnostic`，再按 SSE 返回的 `execution_id` 关联同一次请求；这些记录也都是 INFO 级别。启动配置摘要没有请求，使用 `execution_id=none`。

| event | 默认记录 | 如何理解 |
| --- | --- | --- |
| `ragflow_config` | enabled、地址/知识库 ID 是否已配置、ID 是否有效、Token 来源、正文开关、重排开关与实际启用状态、三个候选数 | 每次应用启动一次；enabled=false 表示跳过真实检索，其余检索选项在 enabled=true 时使用。不打印地址、ID 或 Token 的实际值。候选数为 null 表示该字段不会发送 |
| `ragflow_result` | succeeded、attempts、returned_chunks、kept_chunks、empty_result、保留片段的 ID/文档名/相似度/字符数 | 检索协议解析成功；kept_chunks=0 仍是成功的空结果，模型会使用无资料提示 |
| `ragflow_skipped` | status=skipped、reason=RAGFLOW_ENABLED=false、attempts=0、kept_chunks=0 | 测试开关关闭检索；返回空资料继续模型调用，不代表实际查询成功或未命中。视觉分支本身不检索，不产生此事件 |
| `ragflow_failed` | failed、attempts、业务错误码、经过处理的错误原因和配置存在标记 | 与“没有命中”区分；不打印上游错误正文 |
| `ragflow_content` | 默认关闭；开启后记录 question 和 RAGFlow 返回的 chunks | 包含客户端过滤前的片段，用于排查低分或空白片段被过滤 |
| `model_request` | model、type、provider、stream、body_logged | 默认只记录摘要；开启后附加最终请求 body。记录发生在调用前，不代表模型调用已成功 |

本地在 `.env`、云端在 AgentArts **运行时环境变量**中设置，重新启动相应服务/实例后生效：

```dotenv
RAGFLOW_LOG_CONTENT=true
MODEL_LOG_REQUEST=true
DIAGNOSTIC_LOG_MAX_CHARS=16000
```

两个正文开关默认 false。`DIAGNOSTIC_LOG_MAX_CHARS` 范围为 256～65536，作用于每个事件的脱敏 JSON 数据；超出时 `truncated=true`，data 改为 `{original_chars, preview}`，preview 最多为指定字符数。外层 JSON 转义和日志前缀不计入这个限额。日志始终为单行有效 JSON，实际检索结果、模型请求和 SSE 不受日志截断影响。

`ragflow_content` 是原始检索片段，`ragflow_result.selected` 是按相似度、非空正文和 Top K 筛选后的片段。最终模型上下文还受 `MAX_CONTEXT_CHARS` 限制，因此要确认模型实际携带的知识，应查看 `model_request.data.body.messages`；代理协议查看 `body.model_request.messages`。其中能看到 system/user 消息、stream=true 及透传的思考选项。

模型日志取自 HTTP 调用使用的同一请求体，对副本脱敏，不改变发出的请求：已知鉴权字段和本次使用的凭据值替换为 `<REDACTED>`，图片 URL/Base64 替换为 `<IMAGE_OMITTED>`。请求头不记录。正文仍可能包含用户问题、系统提示及知识库内容，开关只在需要查看这些内容时启用，排查结束可关闭。正文序列化有处理开销，异步输出只避免日志写入阻塞；日志故障不应改变业务结果。

以下为检索成功的示意日志，并非线上实测结果：

```text
workflow_diagnostic execution_id=example event=ragflow_result truncated=false data={"status":"succeeded","attempts":1,"returned_chunks":3,"kept_chunks":1,"empty_result":false,"selected":[{"id":"chunk-1","document":"展品介绍","similarity":0.88,"content_chars":120}]}
```

本地查看：`./scripts/verify_logs.sh logs 实际execution_id` 同时筛选耗时和诊断日志。云端在已采集应用控制台输出的日志流中，搜索 `workflow_diagnostic` 和请求 ID；与 `workflow_timing` 配合判断检索与模型阶段是否完成。异步队列可能丢日志，不能仅凭缺少某条记录断定阶段未执行。

## RAGFlow 地址或知识库 ID 未配置

本次报错环境为 AgentArts，已确认没有配置相关运行时环境变量。旧版这条错误在代码发起 RAGFlow HTTP 请求之前产生：URL 为空，或知识库 ID 为空/格式无效；因此它并不说明知识库网络或服务报错。新版已将错误分别改为缺少 `RAGFLOW_URL`、缺少 `RAGFLOW_DATASET_ID`、`RAGFLOW_DATASET_ID` 格式错误。

在目标 AgentArts 运行时配置以下变量，无需写入代码或业务请求体：

```dotenv
RAGFLOW_URL=http://172.16.0.154:9380/api/v1/retrieval
RAGFLOW_DATASET_ID=e611c8e0c45111f19cb7ff060c22c24e
RAGFLOW_API_TOKEN=替换为实际RAGFlowToken
RAGFLOW_RERANK_ID=bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible
```

前 3 项必需；如使用 `RAGFLOW_API_TOKEN_FILE` 挂载文件，可替代 Token 环境变量。rerank 与接入文档一致，未配置时不发送该字段。`.env` 和文档中的地址不会自动成为运行时配置：`.env` 被排除在 Git 和镜像之外，应用也不会自动加载它，本地是启动脚本负责加载。

保存环境配置并发布/切换目标运行时版本后，用新的 Session ID 调用。修复缺失变量本身无需修改代码或重建原 RAGFlow 镜像；要看到本次新增诊断日志，需要另外部署包含这些改动的新镜像。在新版本启动日志核对 `url_configured=true`、`dataset_id_configured=true`、`dataset_id_valid=true`、`token_source=environment/file`。这仅证明配置存在，Token 是否有效和私网是否可达仍需实际检索验证。

若 `ragflow_failed` 中 `attempts=0` 且配置标记为 false，先修复配置；有请求尝试后再按 HTTP 状态或超时错误排查鉴权/网络。运行时需能访问 `RAGFLOW_URL` 选定的私网 `172.16.0.154:9380` 或公网 `1.95.220.19:9380`；完整 URL 与切换方式见 [环境变量说明](environment-variables.md#公网与私网地址切换)。

## LTS 如何保存日志

LTS 按日志流保存日志，一个日志组可包含多个日志流。日志流默认沿用日志组的保存时间，也可单独设置；普通配置范围为 1–365 天，文档默认值为 30 天。超期日志自动删除。**当前项目实际保存多久，要查看对应日志流的配置，不能直接认定为 30 天。**[来源：管理日志流](https://support.huaweicloud.com/usermanual-lts/lts_04_0004.html)

## 在 LTS 查看与下载

进入 **LTS → 日志管理 → 对应日志组 → 日志流**，选择请求发生的时间范围，查询日志。可查看原文、上下文，并开启自动刷新。查询结果支持前端直接下载为 TXT 或 CSV，单次最多 5,000 条；下载原文的前提是日志流保留了原始日志。[来源：搜索与分析日志](https://support.huaweicloud.com/usermanual-lts/lts_07_0076.html)

全文搜索与字段搜索不同：全文索引按分词规则搜索原文；`execution_id:xxx` 这样的字段查询需要对应字段和字段索引。当前日志中的 `execution_id=xxx` 只是文本形式，不能据此认为 LTS 已自动生成字段。先搜索 `workflow_timing`，再按请求 ID 缩小范围；实际匹配方式取决于该日志流的分词配置。[来源：创建 LTS 日志索引](https://support.huaweicloud.com/usermanual-lts/lts_07_0078.html)

## 长期保存到 OBS

需要长期归档时，可在 **LTS → 日志转储 → 配置转储** 中选择日志流和 OBS 桶。周期性转储用于后续新增日志，不应当作历史数据自动补传；历史数据使用一次性转储能力，需确认租户是否已开通。转储需要 LTS 与 OBS 相关权限，LTS 转储和 OBS 存储分别计费。[来源：日志转储至 OBS](https://support.huaweicloud.com/usermanual-lts/lts_04_0041.html)

## `elapsed_ms` 能否直接统计

查看一条日志的耗时只需要找到原文。计算阶段平均耗时、P95 或绘制趋势图，需要让分析引擎读取可计算的数值，文本中出现 `elapsed_ms=150.0` 本身并不等于完成字段配置。

有两种处理方式：

1. 持续统计：将 `execution_id`、`stage`、`status`、`search_mode` 提取为字符串，将 `elapsed_ms` 提取为数值，并配置需要的字段索引。LTS 支持正则等云端结构化解析；配置变更只作用于新写入日志，历史日志不会自动重新解析。[来源：云端结构化解析](https://support.huaweicloud.com/usermanual-lts/lts_0823.html)
2. 临时分析：在保留原始 `content` 且其具备 SQL 所需索引的前提下，可在查询时从原文提取耗时再转成数值。LTS 管道符方式提供 `regexp_extract` 和 `CAST(... AS double)`；此方案是根据官方功能组合得出的实现路径，尚未在本租户执行验证。[来源：正则式函数](https://support.huaweicloud.com/usermanual-lts/lts_07_0207.html)、[类型转换函数](https://support.huaweicloud.com/intl/zh-cn/usermanual-lts/lts_07_0210.html)

数值可用后，LTS 提供 `avg`、`max`、`approx_percentile` 等聚合函数，可按阶段统计均值、最大值和近似 P95。[来源：聚合函数](https://support.huaweicloud.com/intl/zh-cn/usermanual-lts/lts_07_0204.html)

## 尚需核验的租户配置

- AgentArts 实际关联的区域、日志组和日志流，以及当前用户的查看权限。
- 日志流是否开启存储、实际留存天数、是否保留原文及当前索引配置。
- 使用的日志流是否支持分析。官方区分标准型与搜索型，搜索型不支持 SQL 分析；分析前需核对实际类型。[来源：管理日志流](https://support.huaweicloud.com/usermanual-lts/lts_04_0004.html)
- 是否已配置 OBS 归档、归档任务是否正常、是否开通一次性转储。
- 一次真实调用的日志是否完整到达，以及字段提取、统计查询是否符合预期。
