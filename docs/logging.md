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

应用使用 `SLF4J → Logback 异步队列 → CONSOLE`，输出 `workflow_timing`，没有配置文件 Appender 或在请求线程里调用 LTS API。队列满时可以丢弃日志，以保持业务不被日志写入阻塞。[代码：logback-spring.xml](../src/main/resources/logback-spring.xml)、[代码：WorkflowTiming.java](../src/main/java/com/heilos/travel/WorkflowTiming.java)

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

本项目的所有耗时记录都是 INFO 级别，成功/失败/取消由 `status=succeeded/failed/cancelled` 区分，筛选 ERROR 级别会漏掉这些记录。为保持日志字段兼容，`search_mode` 固定为 `ragflow`；检索阶段记录读取 Token 文件、HTTP 请求、重试及提示词准备的实际耗时。一次请求应有一条 total；异步日志可能略晚于 SSE 结束到达。[本地实现和验证说明](../README.md#阶段耗时日志)

截至本次调研，运行时观测指南仍注明托管智能体的 Trace/Metric 查看功能未开放，而 API 结构里已经存在相应配置字段。因此不能仅凭开了日志开关或 API 中有字段，就认定会自动出现分类/检索/模型的链路图、P95 或 Token 图表；本项目当前提供的是日志打点，可在 LTS 另外分析。[运行时观测指南](https://support.huaweicloud.com/ops-agentarts/agentarts_14_0006.html)、[运行时 API 配置字段](https://support.huaweicloud.com/api-agentarts/ShowCoreRuntime.html)

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
