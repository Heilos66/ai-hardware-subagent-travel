# AME / yingmu 知识库验证

实验分支 `codex/ame-knowledge-latency`。不修改上游 AME、RAGFlow 或现有 Agent 生产源码。AME固定提交 `26ce43e2b34be958b84edb4dcf834291ffa820b6`，较用户文档所列 `2e96e84` 仅多Android集成文档。

这是独立本地召回实验：CSS 仅作为已归档52片段的来源，在线无CSS请求、无MaaS embedding/rerank、无LLM生成。不要将结果当作“保留CSS和bge重排”的完整方案。

## 构建与复测

需要 C++17 编译器、CMake>=3.16、Git、Python>=3.8。macOS和Linux均使用同一上游CMake构建，不需Android SDK/NDK。ECS尚未复测，不提供跨机器性能承诺。

```bash
./tools/ame-benchmark/build.sh
python3 tools/ame-benchmark/run.py \
  --source target/ame-validation/source \
  --binary target/ame-validation/build/ame_kb_worker \
  --out target/ame-validation/your-new-run --rounds 50
```

已有clone可用 `AME_SOURCE=/absolute/path/to/ame ./tools/ame-benchmark/build.sh`。已有构建目录若使用不同源码路径，先换新的构建目录或自行迁移；不要覆盖已有测量输出。

fixtures/corpus.json复用2026-10-21 CSS快照的52个片段，去掉原bge向量，仅保留id、文档名、正文。queries.json为原20问及预期文档。按相同时间戳导入，避免用写入顺序制造时间相关性；每个片段作为单独记录，不重新按Android的200字符切分。graph使用remember_turn("knowledge",...)；lmem使用kind=note（通用笔记，非Android guide→assistant映射），两者均为外部测试夹具，非Android全流程重现。

graph复用Android召回参数lex_topk=30、soft_min_sim=0.2、depth=2、limit=5，中文词典678211条；另测无词典消融和lmem。50轮同20问用于时延分布，不是1000个独立质量样本。每轮固定种子打乱顺序；无答案缓存，每次实际调用引擎。startup/dictionary/import另记，不包含在常驻召回中。

## 当前Java Agent的配置式实验接入

先启动仅监听127.0.0.1的实验适配器：

```bash
read -rsp '设置实验接口Token: ' AME_BENCH_TOKEN; echo
export AME_BENCH_TOKEN
python3 tools/ame-benchmark/serve.py \
  --source target/ame-validation/source \
  --binary target/ame-validation/build/ame_kb_worker
```

另一个终端启动Agent时设置（Token需一致）：

```bash
export RAGFLOW_ENABLED=true
export RAGFLOW_URL=http://127.0.0.1:18087/api/v1/retrieval
export RAGFLOW_DATASET_ID=ame-yingmu
export RAGFLOW_API_TOKEN='与AME_BENCH_TOKEN相同的值'
export RAGFLOW_API_TOKEN_FILE=''
export RAGFLOW_USE_RERANK=false
export RAGFLOW_TOP_K=5
export RAGFLOW_SIMILARITY_THRESHOLD=0
export RAGFLOW_MIN_SIMILARITY=0
export RAGFLOW_RETRIES=0
```

这些是**实验替换配置**，没有自动写入.env或部署。Agent若在容器内，容器127.0.0.1不等于宿主机，本文回环方案不可直接照搬。适配器仅开发验证用途，没有热更新、租户隔离、并发容量、生产运维保障。C++ worker常驻并串行调用；启动导入约十余秒应在ready之前完成。

AME原始分数不是bge概率，不能复用0.2/0.3阈值，实验明确设0；这也意味着没有业务相关性拒绝门槛。带rerank_id的请求明确400拒绝，绝不假装已执行bge重排。当前适配器不实现CSS向量参数，knn_top_k等旧字段不影响AME算法。

接口与真实Java客户端验证：

```bash
python3 tools/ame-benchmark/http_bench.py --out target/ame-validation/http-new
AME_TEST_URL=http://127.0.0.1:18087/api/v1/retrieval \
  ./mvnw -Dtest=AmeRetrievalIntegrationTest test
```

两个命令需设置AME_BENCH_TOKEN。Java测试为opt-in，未设置AME_TEST_URL时跳过；调用真实RagFlowClient，未模拟AME响应，不调用生成模型。其200次时延包含Java WebClient、响应解析和当前客户端诊断；不能与仅C++ recall混称。Python HTTP基准用新TCP，Java WebClient可能复用连接，分别记录。

## 质量探索与边界

```bash
python3 tools/ame-benchmark/probe.py \
  --source target/ame-validation/source \
  --binary target/ame-validation/build/ame_kb_worker \
  --out target/ame-validation/probes-new
```

probes.json是本次新增12个改述问题和2个库外问题，单独报告，不混入原20问。预期文档根据语料编写；不等同片段证据人工金标，也不是答案正确率。库外有返回片段不自动等同LLM答错，但说明需要独立拒答/回退验证。

若要求在线必须保留CSS和MaaS rerank，应另做“AME候选→CSS按ID取原文→MaaS rerank”或辅助记忆方案；需要内容版本同步、删除、权限和原始数据一致性校验。本目录未实现/测量该链路，不能把本地召回时延与历史模型分位数相加当作实测。
