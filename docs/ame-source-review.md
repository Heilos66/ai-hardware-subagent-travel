# AME dev-2.0 源码核对与知识库接入边界

核对日期：2026-10-26。范围：官方源码、Android 集成文档与现有 CSS/MaaS 知识库的可比性；本笔记不包含模型请求或性能实测。

## 版本

实际分支 HEAD 为 `26ce43e2b34be958b84edb4dcf834291ffa820b6`。`git diff 2e96e84 HEAD` 只新增 `android/AME-Android集成文档.md`（170行），没有引擎或 App 代码改变。因此用户提供的 `2e96e84` 代码口径与当前 HEAD 一致。[提交比较](https://github.com/arrayforward/ame/compare/2e96e84...26ce43e2b34be958b84edb4dcf834291ffa820b6)

## 对本任务的结论

**AME 可作为独立的本地知识片段召回实验，但当前仓库不是可直接配置 CSS 为存储后端的 RAG 服务。默认快速路径不调用 MaaS embedding / rerank。** 因此本地 AME 时延若显著低于既有 CSS+MaaS，只能说明替换了检索与排序机制后的性能，不能归因于“相同模型链路的框架优化”。原有“CSS 必须在查询链路、保留 bge 重排、不自编译”的约束若继续适用，AME 默认部署不满足。

| 项目 | 源码事实 | 接入含义 |
|---|---|---|
| graph 后端 | `Engine` 持有具体 `Storage`、`VectorService` 等成员；`Storage` 是本地节点/边/向量存储，快照 JSON | 没有 endpoint/user/password/index 形式的 CSS 配置，也没有可注入的 CSS 存储接口 |
| 默认 embedding | `HashEmbedder` 为字符 n-gram hashing、128维；`Engine` 构造使用 `vector_(&storage_)` | 不是 bge-m3 的1024维向量；不能复用已有 bge 向量作为同一种表示 |
| embedding 扩展 | `IEmbedder` 接口存在，`VectorService` 构造可接实现；但 `Engine` 构造没有该参数，也无公开替换 setter | 要接远程 embedding 需额外适配与装配，不能仅更换环境变量 |
| graph 重排 | M7 使用图扩散、余弦、时间/importance 融合 | 不是 bge-reranker-v2-m3 交叉编码模型，不应将其计时列名写成 MaaS rerank |
| lmem 后端 | `Mem` 聚合本地 ObsStore/FactStore/MemIndex/View；分片 append 持久化 | 也无 CSS 后端适配；不使用 graph 的128维 embedding |
| Android 深度档 | 召回60条，`MiniMaxClient.rerank` 每条截200字符，一次 chat 请求选top5；异常回退原序 | 是额外远程 LLM 调用，并非 MaaS `/v1/rerank`；不得推断其满足100ms |

证据：[Engine API](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/engine/include/ame/engine/engine.h)、[Engine 装配](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/engine/src/engine.cpp)、[Storage API](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/storage/include/ame/storage/storage.h)、[VectorService/IEmbedder](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/vector/include/ame/vector/vector_service.h)、[M7排序](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/rerank/src/reranker.cpp)、[Mem API](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/mem/include/ame/mem/mem.h)、[MiniMax重排实现](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/android/app/src/main/java/com/ame/memory/MiniMaxClient.java)。对核心模块及 Android `.h/.cpp/.java` 搜索 `opensearch|elasticsearch|modelarts|maas` 未发现适配实现；以上“不支持”指该审阅版本的现有实现，不代表无法另行开发。

## 可直接使用的 C++ 接口与公平测试设置

- graph：`ame::Engine`；`remember_turn(speaker,text,ts,dia_id,session_id)` / `remember_event` 写入；`recall(SeedQuery, RecallFilters, depth, limit, explain)` 读取；`save/load` 保存恢复。
- Android graph 默认对应 `SeedQuery.text=query; lex_topk=30; soft_min_sim=0.2; recall(..., depth=2, limit=5, explain=false)`。直接用 C++ 默认阈值会与 Android 路径不一致。[JNI 调用](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/android/ame_jni/ame_jni.cpp)
- lmem：`ame::Mem(root)`；`remember(owner,channel,kind,text,ts,meta)`；`recall(query,owner,k,now)`。`obs(ref_obs)` 可映射原始片段 ID；`MemIndex` 是 CJK 二元组+ASCII词倒排，`View` 按 IDF、来源可靠性、时间等打分。[Mem API](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/mem/include/ame/mem/mem.h)、[MemIndex](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/memindex/include/ame/memindex/memindex.h)、[View](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/memview/src/memview.cpp)
- 使用同一份已切分知识片段、相同问题、相同返回top5；固定摄入时间与查询时间，避免记忆时间衰减造成额外偏差。graph 的 `limit=8/32` 是结果截断，不自动等价于现有 CSS 的 rerank 候选数。
- 时延分开记初始化/词典加载、摄入、热态 recall、HTTP 服务开销；进程内微基准不可直接与 ECS 完整 HTTP E2E 宣称倍数优势。
- 优先检查证据充分率、首条/前5条片段、空召回及难题。仓库 LoCoMo/sample50 指标不是当前文旅知识库准确率；文档“非LLM路线被证伪”的结论只覆盖作者的实验数据，不能外推所有知识库。

## 中文词典与 Android 文档差异

graph 中文测试需加载实际词典；Android Gradle 拷贝 `lexicon/lexicon_src/segmentation/merged.txt`，JNI 用 `extractor().load_keyword_dict_cached`。词典未加载时有字符二元组兜底，但不是同一配置。lmem 内置词法，不调用该词典。[Gradle](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/android/app/build.gradle)、[JNI词典](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/android/ame_jni/ame_jni.cpp)、[词库说明](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/lexicon/README.md)

Android 文档有两处表述需要收窄：

1. 称“24个C++模块”与实际 CMake 不符；JNI 列表是15个1.x模块（含core）+5个2.0模块，共20个，逐个 `add_subdirectory`，并非直接 add 根 CMake。
2. 业务逻辑“纯Java+OkHttp+org.json”是架构意图，但实际 `MemoryOrchestrator` / `MiniMaxClient` 导入 `android.util.Log`，还依赖生成的 `BuildConfig`；移植普通JVM/Spring仍需适配这些边界，不能原样运行。

证据：[JNI CMake](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/android/ame_jni/CMakeLists.txt)、[MemoryOrchestrator](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/android/app/src/main/java/com/ame/memory/MemoryOrchestrator.java)、[MiniMaxClient](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/android/app/src/main/java/com/ame/memory/MiniMaxClient.java)。

## 许可证信息

该 HEAD 的已跟踪文件中未找到 `LICENSE` / `COPYING`，README 未声明整体软件许可证；不要将“公开源码”直接标记为已确认 MIT/Apache 等开源许可。词典 README 列出 WordNet、funNLP整理来源和LLM蒸馏产物说明，但不能替代引擎整体授权。商用分发前应向维护者确认引擎及实际使用词典的许可。[源码树](https://github.com/arrayforward/ame/tree/26ce43e2b34be958b84edb4dcf834291ffa820b6)、[词库来源](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/lexicon/README.md)

## 保留 CSS 的后续可测路线（设计建议，不是已实现能力）

1. CSS 仍做权威内容存储，离线导出片段构建 AME 本地索引；在线用 AME 返回片段 ID，再从 CSS 批量获取原文。需同步删除/更新、权限过滤及版本，且不再是原来的 CSS 向量召回。
2. CSS 原有检索结果与 AME 记忆证据融合，保留 MaaS rerank。此时模型成本仍在，额外 AME 不天然降低延迟；要用完整链路实测验证。
3. AME 前置缓存/快速通道、低置信回退现有 CSS+MaaS：需要独立标注命中率、快通道误命中、回退率、全流量P95，不能只报缓存命中的时延。

本次先以不修改上游源码的外部基准程序验证 AME 独立召回可行性，再决定是否实现上述适配；不要把独立 AME 结果描述为已完成 CSS 后端接入。

## 导游导入的实际参数

`MainActivity.importGuidePdf` 将 PDF 文本按200个 Java UTF-16 code units切块，最多300块；无每块标题前缀；graph 先加载词典，再调用 `eng.rememberTurn("guide", chunk, base+i, "g"+i, sessionId)`，base 为导入时的 epoch 秒。graph JNI 原样透传 `Engine::remember_turn`。lmem 的 Java wrapper 则把所有非 `user` speaker（包括 `guide`）映射成 `assistant`，且没有传递 diaId；因此忠实复现时知识片段会按助手来源打分。若基准改用 `note`/`user` 或添加片段meta，应明确标注为知识库适配设置，而非Android原样复现。

来源：[导入逻辑](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/android/app/src/main/java/com/ame/memory/MainActivity.java#L143-L160)、[lmem映射](https://github.com/arrayforward/ame/blob/26ce43e2b34be958b84edb4dcf834291ffa820b6/android/app/src/main/java/com/ame/memory/LmemEngine.java#L61-L63)。
