# AME 3.0 集成与同机验证

已接入 AME dev-3.0，保持 Java Agent 的检索调用契约，完成本地引擎、HTTP、实际 Java 客户端及 ARM64 容器验证。默认使用 graph 原排序，cheap 特征重排作为可选模式。

本轮3.0 graph与2.0 graph的20问Top5片段及顺序完全一致，P95基本持平。cheap没有改善本中文语料的质量，首条文档命中从20/20降至18/20，因此不跟随上游Android默认开启cheap。两种模式仍不调用在线CSS、MaaS embedding或MaaS rerank。

## 版本与实现变化

- 上游3.0固定提交：[`df68cbf1f2742095901ec97434507d76cba2f61d`](https://github.com/arrayforward/ame/tree/df68cbf1f2742095901ec97434507d76cba2f61d)。
- 2.0对照固定提交：`26ce43e2b34be958b84edb4dcf834291ffa820b6`。版本统一登记在 [versions.json](../tools/ame-benchmark/versions.json)。
- 3.0移除lmem分层存储及thinker、feedback、learning等实验模块。适配器通过CMake目标检测支持旧版lmem，在3.0选择lmem明确失败；不伪造兼容。
- graph继续使用公共Engine API，语料导入和检索参数保持不变，未修改上游源码。
- cheap复用固定版Android JNI的评分函数，去掉JNI句柄表后保存在 `cheap_rerank.hpp`；逐段核对与上游函数体一致。使用上游 `android/app/src/main/assets/cheap_model.json`，候选池60→11特征评分→Top5；实际可用事件最多52。该模式包含扩池和重排两项变化，不能将质量差异完全归因于单个特征。
- 上游cheap的内容词、英文专名特征主要处理ASCII；纯中文问题往往没有这些信号。本模式未移植bench的完整entity-pass/guard/LLM流程，不应套用LoCoMo指标。
- cheap与上游JNI一致，仅改变排序，返回score仍是原融合分，不是cheap概率。请求中的`rerank_id`继续表示远程MaaS重排，返回400，不偷偷改成cheap。
- 3.0上游带MIT许可，已保存许可文本并放入容器 `/app/ame/LICENSE`。

## 测试条件

2026-10-26，Mac ARM64，Release C++构建，单并发常驻进程，52个原知识片段、4份文档、原20问；每种模式20次预热，50轮共1000次引擎查询。词典678211条；lex_topk=30、soft_min_sim=0.2、depth=2、输出Top5；统一导入时间，保留片段ID。

最终对照按2.0 graph→3.0 graph→3.0 cheap顺序执行，未与容器编译并行，未做多批次随机交错A/B。首轮探索数据未用作最终比较。重复查询只增加时延样本，独立质量样本仍为20题；所有结果不含最终LLM生成、AgentArts网关、冷启动和并发排队。

## 同机引擎结果

| 模式 | recall P50 ms | recall P95 ms | 最大ms | 首条目标文档 | Top5目标文档 | 目标文档片段/返回片段 |
|---|---:|---:|---:|---:|---:|---:|
| 2.0 graph | 7.146 | 8.985 | 10.256 | 20/20 | 20/20 | 91/100 |
| 3.0 graph | 7.085 | 8.949 | 9.998 | 20/20 | 20/20 | 91/100 |
| 3.0 cheap | 7.243 | 9.073 | 10.291 | 18/20 | 20/20 | 80/100 |

3.0 graph与2.0相比仅约0.036ms的P95差异，单轮测试不能认定为稳定加速。3.0 graph全部20问的Top5 ID及顺序与2.0一致。cheap全部20问排序/候选组合均变化，首条未命中问题为“唐三彩载乐骆驼俑有什么特点？”和“唐三彩载乐骆驼俑反映了怎样的文化交流？”。

目标文档片段比例不是语义Precision，其他文档也可能提供相关证据。本文未标注完整答案金标，不将文档命中率写成答案准确率。

## HTTP 与 Java 实际调用

HTTP每种模式20次预热、200次正式请求，新建回环TCP连接；Java使用实际RagFlowClient和默认连接池，20次预热、200次正式调用。

| 调用边界 | P50 ms | P95 ms | 最大ms | 正式请求 |
|---|---:|---:|---:|---:|
| 3.0 graph HTTP | 7.809 | 9.758 | 10.455 | 200/200成功 |
| 3.0 cheap HTTP | 7.848 | 9.922 | 10.760 | 200/200成功 |
| 3.0 graph Java客户端至解析完成 | 8.754 | 10.668 | 11.526 | 200/200通过断言 |

本机暖机检索均低于100ms，不等于AgentArts线上P95已经达标。cheap没有执行Java的20问首条全命中断言，因为其已知质量不满足该断言；其真实Java→AME→模型请求路径另由容器冒烟测试验证。

## 改述与无答案问题

| 模式 | 改述首条文档 | 改述Top5文档 | 两个库外问题 |
|---|---:|---:|---|
| 3.0 graph | 12/12 | 12/12 | 均返回5片段 |
| 3.0 cheap | 11/12 | 12/12 | 均返回5片段 |

改述来自已有探索集，非盲测。天气、车票仍会召回无关文物内容，3.0没有解决业务相关性拒绝问题；当前保持阈值0，以免把AME分数当作bge相似度。最终回答正确性、忠实性和库外拒答需继续在真实模型及业务金标上验证。

## 接入配置与回退

默认构建版本改为3.0，旧版仍可通过 `AME_VERSION=2.0` 构建。Java生产源码未修改，依然使用兼容检索接口。

| 容器变量 | 默认 | 作用 |
|---|---|---|
| AME_ENABLED | true | 使用本容器AME；false恢复原RAGFLOW环境配置 |
| AME_RETRIEVAL_MODE | graph | graph或graph-cheap；默认不启用cheap |
| AME_CHEAP_MODEL | /app/ame/cheap_model.json | cheap使用的模型；缺失时启动失败 |
| RAGFLOW_TOP_K | 5 | 最终片段数，平台可覆盖 |

其余RAGFLOW变量的自动覆盖行为与2.0镜像相同。入口仍为 `python3 /app/start.py`、端口8080、健康检查 `/ping`。不要用 `java -jar` 覆盖入口。容器内AME仍只监听127.0.0.1:18087。

3.0 graph本机词典加载约284ms、语料导入约13.96秒，引擎启动约14.25秒，Java启动另计。实例重启重新导入，worker依然串行处理请求；本轮没有完成并发容量和持久化快照验收。

## 验证记录

| 检查 | 本轮结果 |
|---|---|
| 上游3.0 test_engine | 13/13通过；未声称上游全套ctest通过 |
| 2.0兼容构建与复测 | 构建通过，1000次graph查询 |
| 3.0原排序及cheap | 各1000次查询、各200次HTTP请求 |
| Java AME集成测试 | 1通过、0跳过 |
| RagFlowConfigurationTest | 5通过、0跳过 |
| 鉴权、旧阈值、错误数据集、MaaS rerank请求 | 两模式分别验证401/400 |
| 3.0 lmem、缺失cheap模型 | 启动非零退出，未静默回退 |
| Python语法、Shell语法、git diff检查 | 通过 |
| ARM64容器 | graph/cheap均通过健康检查、非root、真实知识片段进入模型请求、SSE及日志；关闭AME路径通过 |

容器使用模拟对话模型，只证明检索与Agent编排接通，不证明真实模型回答正确。首次本机服务启动受沙箱监听权限限制，取得工具执行许可后重新完整测试成功；该次环境失败不计入时延样本。

本地镜像：`travel-agent:ame-v3-validation`，Docker V2清单摘要 `sha256:b7e5841c080c8eff427baa1bbf5092e614e70c814495c0e1fa41d3aebf89cc75`。本轮未推送SWR、未更新AgentArts；当前已发布2.0镜像不受影响。

## 复现与原始证据

运行说明和容器命令见 [AME验证README](../tools/ame-benchmark/README.md)。本机集成测试可用：

```bash
python3 tools/ame-benchmark/validate_local.py \
  --source target/ame-3.0/source \
  --binary target/ame-3.0/build/ame_kb_worker \
  --out target/ame-3.0/integration-new
```

需可用的Java21和本地端口监听权限；准备容器上下文的Python需支持 `tarfile.extractall(filter=...)`（建议Python3.12+）。输出目录不得已存在，避免覆盖历史数据。

- [版本、二进制哈希与测试范围](../artifacts/ame-v3-20261026/manifest.json)
- [两版逐题Top5及汇总](../artifacts/ame-v3-20261026/comparison.json)
- [2.0本轮1000次原始样本](../artifacts/ame-v3-20261026/baseline/graph-dict-samples.jsonl.gz)
- [3.0 graph 1000次样本](../artifacts/ame-v3-20261026/final/graph-dict-samples.jsonl.gz)、[cheap 1000次样本](../artifacts/ame-v3-20261026/final/graph-cheap-samples.jsonl.gz)
- [graph HTTP 200次](../artifacts/ame-v3-20261026/integration/graph-http/samples.jsonl.gz)、[cheap HTTP 200次](../artifacts/ame-v3-20261026/integration/graph-cheap-http/samples.jsonl.gz)
- [Java 200次样本](../artifacts/ame-v3-20261026/integration/java-client-samples.json)、[改述与库外结果](../artifacts/ame-v3-20261026/probes/summary.json)
- [容器graph回执](../artifacts/ame-v3-20261026/container/graph/receipt.json)、[容器cheap回执](../artifacts/ame-v3-20261026/container/cheap/receipt.json)
- [全部归档校验和](../artifacts/ame-v3-20261026/SHA256.json)

建议继续使用3.0 graph进行线上小范围验证。当前收益是兼容上游精简后的实现并保留可选重排能力；不要以版本号升级承诺时延或准确率提升。
