# AME 接入 yingmu 知识库：时延与质量验证

测试日期：2026-10-26。工作分支：`codex/ame-knowledge-latency`。

## 1. 结论

AME graph 可以把本轮小知识库的**本地在线召回**控制到约10ms以内，兼容HTTP接口也已完成真实调用验证。但它通过本地哈希向量、图扩散与评分完成检索，跳过在线CSS和MaaS embedding/rerank，**不能据此认定原“CSS + MaaS重排”方案已经达到100ms**。

原20问的文档命中表现良好；新增库外问题仍返回文物片段，且文档命中不能代替答案证据充分性。本轮结论是“适合作为快速检索候选进行后续业务验证”，不是质量无损或生产上线验收。lmem本轮常驻召回P95超过100ms，不建议优先替换graph。

## 2. 测试环境、版本与范围

- 本轮运行在开发者Mac（arm64），非此前AMD64 ECS；平台版本见[运行清单](../artifacts/ame-kb-20261026/local-20261026/manifest.json)。不能将不同机器的时延差直接归因于框架。
- AME固定提交：`26ce43e2b34be958b84edb4dcf834291ffa820b6`，分支dev-2.0。与用户指定文档基准2e96e84的代码相同，只新增集成文档。
- 上游C++17源码未修改；AppleClang Release编译。外部worker链接上游库，无Android SDK、无JNI；HTTP适配器为Python标准库，用持久C++子进程串行处理检索。
- 复用2026-10-21 CSS归档的52个片段、4份文档、原20问。原始bge向量未加载到AME；正文和chunk ID保留，输入SHA256见清单。
- 每个片段一个记录，同一导入时间戳。graph用`remember_turn("knowledge",...)`，lmem用`kind=note`；未照搬Android的200字符切分、逐块递增时间和guide→assistant映射，也不模拟手机会话生命周期。
- graph参数遵循Android召回：lex_topk=30、soft_min_sim=0.2、depth=2、limit=5；加载678211条中文分词词典。无词典单独作为消融实验，不能只因为本轮题目简单就删词典。
- 单并发、每种引擎20问预热后执行50轮，共1000次。每轮固定随机种子打乱顺序，无答案缓存、无远程API调用，故不需要MaaS60秒冷却。重复提问仅用于时延观察，质量样本仍为20个不同问题。
- 性能为常驻进程内存态，导入及词典加载单独计时；不是冷启动每次请求，也不是持续写入/多租户/高并发测试。
- 分位数为nearest rank。原生recall计时不含输出序列化；worker往返包含进程通信和JSON处理；HTTP、Java客户端另列。

## 3. 结果

### 3.1 常驻引擎

| 方案 | 次数 | recall P50 / P95 ms | worker往返 P50 / P95 ms | 首条目标文档命中 | Top5目标文档命中 |
|---|---:|---:|---:|---:|---:|
| graph-dict | 1000 | 7.085 / 8.967 | 7.135 / 9.039 | 20/20 | 20/20 |
| graph-no-dict | 1000 | 0.664 / 0.856 | 0.709 / 0.904 | 20/20 | 20/20 |
| lmem | 1000 | 126.721 / 146.222 | 126.835 / 146.319 | 15/20 | 20/20 |

原20问均有返回。graph有/无词典均命中文档20/20，但这组问题多数直接给出了文物名，不能证明无词典泛化能力等价。lmem首条文档未命中的问题编号见summary；top5仍包含目标文档。

### 3.2 接口及客户端

带词典graph的兼容HTTP接口：20次暖机，200次正式请求，E2E P50 **7.503ms**，P95 **9.371ms**，最大 **10.702ms**。每次新建回环TCP连接，20个不同问题首条文档均命中。鉴权错误返回401；请求MaaS rerank、错误知识库或沿用0.2阈值均返回400，未静默降级。

现有Java `RagFlowClient` 实际连接同一接口：20次暖机后200次正式查询，P50 **8.672ms**，P95 **10.278ms**，最大 **11.615ms**。集成测试1/1通过，未跳过，逐次核对非空、首条目标文档、片段ID与正文。该计时包含WebClient、JSON解析和当前客户端诊断，使用默认连接池，不执行最终LLM生成。见[Java汇总](../artifacts/ame-kb-20261026/java-client-summary.json)、[原始200次记录](../artifacts/ame-kb-20261026/java-client-samples.json)、[测试结果](../artifacts/ame-kb-20261026/java-integration-test.txt)。

### 3.3 启动成本

| 方案 | 词典加载 ms | 52片段导入 ms | 进程就绪 ms |
|---|---:|---:|---:|
| graph-dict | 341.1 | 13968.2 | 14712.8 |
| graph-no-dict | 0.0 | 4.6 | 8.2 |
| lmem | 0.0 | 133.5 | 136.7 |

graph带词典导入约14秒，不能每次请求启动引擎。需要常驻实例和明确ready门控；本轮没有验证从持久快照加载、增量更新或退出保存成本。词典缓存文件不入Git，原始词典由上游固定版本提供。

## 4. 质量与接受边界

增加12个带描述/器号的改述问题，以及天气、车票两个库外问题，仅用于探索：

| 方案 | 改述首条文档命中 | 改述Top5文档命中 | 天气/车票等库外问题返回片段数 |
|---|---:|---:|---|
| graph-dict | 12/12 | 12/12 | {'u01': 5, 'u02': 5} |
| graph-no-dict | 12/12 | 12/12 | {'u01': 3, 'u02': 0} |
| lmem | 10/12 | 12/12 | {'u01': 5, 'u02': 5} |

这些改述问题由本次根据已知语料编写，并非盲测集；不能与20问合成宣传为独立大样本。库外有片段不代表LLM一定答错，但说明本地召回结果本身不能用作“问题属于知识库”的可靠判据。

例如“长信宫灯是用什么材料制成的？”：graph首条命中长信宫灯文档中的造型结构片段，材质关键证据位于后续结果。首条文档正确并不意味着首条已回答问题。需另建片段证据金标、歧义/跨文档/无答案集合，并用实际Agent验证回答质量。

## 5. 与原三方案的关系

此前ECS、保留MaaS embedding和rerank的8候选E2E P95：RAGFlow 5269.1ms，CSS原生3757.5ms，WeKnora 515.6ms。它们包含远程模型调用，而本轮AME不包含，且机器与时间不同，故不计算“优化百分比”，也不把旧模型P95和新AME P95相加当作实测。

如果仍要求在线使用华为CSS且保留MaaS rerank，可评估：AME离线同步片段→本地生成候选ID→CSS按ID取权威原文→MaaS rerank。该方案可能省去在线query embedding，但必须另实现候选映射、版本/删除同步、权限过滤及失败回退，并重新测量质量和完整时延；本次未实现、未部署该混合链路。

若只是把AME作为原CSS检索之前的辅助记忆层，原两次模型调用仍在，还会增加一段本地处理，不能预设其降低检索时延。MiniMax深度重排也不是现有bge rerank的等价替换。

## 6. 可复现接入与产物

- [构建、运行、Agent环境变量、测试命令](../tools/ame-benchmark/README.md)
- [固定52片段](../tools/ame-benchmark/fixtures/corpus.json)、[原20问](../tools/ame-benchmark/fixtures/queries.json)、[探索问题](../tools/ame-benchmark/fixtures/probes.json)
- [引擎汇总](../artifacts/ame-kb-20261026/local-20261026/summary.json)、[HTTP汇总](../artifacts/ame-kb-20261026/http-20261026/summary.json)、[探索汇总](../artifacts/ame-kb-20261026/probes-final-20261026/summary.json)
- [graph原始请求](../artifacts/ame-kb-20261026/local-20261026/graph-dict-samples.jsonl.gz)、[无词典原始请求](../artifacts/ame-kb-20261026/local-20261026/graph-no-dict-samples.jsonl.gz)、[lmem原始请求](../artifacts/ame-kb-20261026/local-20261026/lmem-samples.jsonl.gz)、[HTTP原始请求](../artifacts/ame-kb-20261026/http-20261026/samples.jsonl.gz)
- [源码核对与固定版本引用](ame-source-review.md)

原始JSONL以gzip无损压缩，保留每次返回片段及耗时；统计基于原始数据。上游源码位于临时目录，长期复现使用build.sh重新获取固定提交。没有将旧知识库服务或生产.env切换到AME。

源码核对发现仓库未提供整体LICENSE/COPYING文件，生产采用前需向维护者确认授权；不影响本轮已完成的本地技术验证。

## 7. 验证记录

上游test_engine 13/13、test_lstore 248/248通过；现有RagFlowConfigurationTest 5/5通过；Python脚本语法与构建脚本语法检查通过。实验HTTP接口对无效鉴权、错误知识库、非零旧阈值和不支持的MaaS重排均验证了明确失败；正常20问全部成功。AME的NotFound被适配为正常空结果，不当作引擎故障。未运行云端模型、未修改生产配置。
