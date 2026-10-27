# 标准 RAG 检索 API · 文旅 Agent 接入说明（Spring AI 1.0 / Java 版）

> 工程已按此文档接入 RAGFlow，以下“本工程接入与验证”为当前实现；后面的原始示例保留作接口参考。真实值从运行环境注入，不随 Git 或镜像发布。本机真实接口尚未连通，验证状态见 [验证指南](verification.md#ragflow-接入验证2026-10-10)。

## 本工程接入与验证

2026-10-12：根据新提供的 v2.0 接入说明，新增重排开关与 A1 候选数参数。使用现有异步 WebClient 实现 [RagFlowClient](../src/main/java/com/travel/agent/RagFlowClient.java)，由 [ExternalServices](../src/main/java/com/travel/agent/ExternalServices.java) 接入原有检索入口。沿用 Java 21、Spring Boot 3.5.15、Spring AI 1.0.9，不增加 Maven 依赖。没有使用下面示例的同步 RestClient、`.call()` 或替换控制器。

三条路径保持原有规则：纯文本使用原问题查询 RAGFlow；图片分类名称非空且置信度 ≥ 0.7 时，以“检索关于{名称}的文档”查询同一个 travel_kb，再调用文本模型；其余图片调用视觉模型。检索片段交给现有 Prompts 构建上下文，仍使用原有纯文本、短回答提示词；不会要求模型展示 document_keyword 等来源元数据。模型 stream=true、思考参数透传及 SSE 协议保持不变。

### 配置

以 [.env.example](../.env.example) 为模板。下面所有变量配置在本地 `.env` 或 AgentArts 运行时环境中，不加入业务请求体。完整必填条件、默认值、重排开关及候选数配置说明见 [环境变量配置文档](environment-variables.md)：

| 变量 | 当前配置或默认行为 |
| --- | --- |
| `RAGFLOW_URL` | 私网 `http://172.16.0.154:9380/api/v1/retrieval` 或公网 `http://1.95.220.19:9380/api/v1/retrieval`，必须是完整 HTTP(S) URL；通过环境变量选择，不自动切换 |
| `RAGFLOW_DATASET_ID` | `e611c8e0c45111f19cb7ff060c22c24e` |
| `RAGFLOW_API_TOKEN` | 部署时注入真实 Bearer Token |
| `RAGFLOW_API_TOKEN_FILE` | 可选 UTF-8 文件，优先于环境 Token，每次查询异步重读；文件不可读时返回配置错误 |
| `RAGFLOW_RERANK_ID` | 模板为 `bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible`；仅在开关为 true 且 ID 非空时发送 |
| `RAGFLOW_USE_RERANK` | 默认 true；false 时不发送 rerank_id 和 rerank_candidates_count |
| `RAGFLOW_KNN_TOP_K` | 默认 64，发送 knn_top_k；显式空值或 null 时省略 |
| `RAGFLOW_KNN_NUM_CANDIDATES` | 默认 128，发送 knn_num_candidates；显式空值或 null 时省略 |
| `RAGFLOW_RERANK_CANDIDATES_COUNT` | 默认 32，仅实际启用重排时发送 rerank_candidates_count；显式空值或 null 时省略 |
| `RAGFLOW_TOP_K` | 默认 3，范围 1～50 |
| `RAGFLOW_SIMILARITY_THRESHOLD` | 默认 0.2，范围 0～1，发送到 RAGFlow |
| `RAGFLOW_MIN_SIMILARITY` | 默认 0.3，范围 0～1，在客户端过滤低于此值的片段 |
| `RAGFLOW_TIMEOUT` | 默认 10 秒，包含凭据读取、所有请求与退避的整次检索时限 |
| `RAGFLOW_RETRIES` | 默认 2，范围 0～2；仅连接异常、HTTP 408/429/5xx 重试，退避 100/200ms |
| `RAGFLOW_LOG_CONTENT` | 默认 false；true 时记录脱敏且限长的原始检索片段 |
| `MODEL_LOG_REQUEST` | 默认 false；true 时记录脱敏且限长的最终模型请求体 |
| `DIAGNOSTIC_LOG_MAX_CHARS` | 默认 16000，范围 256～65536；超出后输出带标记的 JSON 预览 |

知识检索统一使用 RAGFlow，无需选择检索提供方；超时由 `RAGFLOW_TIMEOUT` 控制。

2026-10-12 用户补充上述公网入口。现有代码和已发布镜像均支持 `RAGFLOW_URL` 配置，切换地址后重启/发布运行时配置即可；真实检索需保持 `RAGFLOW_ENABLED=true`。下方原始参考中的“无 EIP”和公网占位符属于提供方当时的部署记录，不代表当前公网地址不可配置。本次未对新公网入口进行真实连通性与鉴权验证。

### 请求参数与回滚

本工程默认使用新文档 A1 的候选数。以下为已配置 rerank ID、启用重排时的请求示例：

```json
{
  "dataset_ids": ["e611c8e0c45111f19cb7ff060c22c24e"],
  "question": "兵马俑是什么时候发现的",
  "page": 1,
  "page_size": 3,
  "similarity_threshold": 0.2,
  "rerank_id": "bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible",
  "knn_top_k": 64,
  "knn_num_candidates": 128,
  "rerank_candidates_count": 32
}
```

三个候选数接受正整数，客户端范围 1～2147483647；不等于最终返回条数 `page_size`。显式设为空字符串或 `null` 时省略对应字段，单独省略互不影响。关闭重排时设 `RAGFLOW_USE_RERANK=false`，即使仍保留 ID，也不会发送重排 ID 和候选数，但两个 kNN 参数仍按配置发送。

恢复服务端默认候选数：

```dotenv
RAGFLOW_KNN_TOP_K=null
RAGFLOW_KNN_NUM_CANDIDATES=null
RAGFLOW_RERANK_CANDIDATES_COUNT=null
```

删除变量会恢复本工程 64/128/32 的默认值；环境变量更改后需要重启/发布对应运行时配置。启动日志 `ragflow_config` 会输出 `use_rerank`、`rerank_enabled` 和有效候选数，数值为 null 表示不发送；不打印 Token 或重排模型 ID。

提供方文档称其服务端未传字段时采用 1024/2048/100，并报告 56-chunk 中文库上 A1 开重排方案的中位检索时延由 0.297s 降至 0.263s，少量查询 Top3 及顺序一致。此为提供方测试结果，不代表本工程、AgentArts 或其他 RAGFlow 部署的性能保证；扩大语料后需重新核对召回率。

要求 HTTP 成功且 JSON 业务 code 为数字 0，再解析 data.chunks。保持服务端片段顺序，过滤空正文和低分片段，最多保留 TopK，并沿用 `MAX_CONTEXT_CHARS=16000` 的上下文限制。未传 keyword 或向量权重，采用服务端默认配置。

正常的空 chunks 或过滤后为空时继续原有无资料问答；HTTP、业务、格式、超时错误返回 E5002，配置或凭据缺失返回 E5004，均停止模型调用。错误不会被隐藏为空资料。下游断连会取消检索及重试。

### 本地与云端验证

```bash
# 自动化测试与打包；不需要真实业务凭据
./mvnw verify

# JDK 21；在同一终端加载本地配置，启动真实 RAGFlow 模式
set -a
source .env
set +a
java -jar target/travel-agent.jar

# 另一个终端：使用本地 IO 请求，默认端口 8080
python3 scripts/test_io_spec.py --port 8080 --spec references/io_spec.md
```

也可使用 `./scripts/verify_logs.sh start`：加载 .env 中的真实 RAGFlow 配置，默认端口 8081，并将日志保存到 target/local-logs/application.log。直接启动 JAR 的日志输出在终端。环境变量修改后重启服务；仅 Token 文件内容更新无需重启。没有知识库网络或凭据时可先测试能力查询和非法输入，正常文本/高置信度图片请求需要真实检索成功。

真实接口可达后，分别以“兵马俑是什么时候发现的”“长信宫灯有什么特点”“唐三彩载乐骆驼俑是什么”“三星堆青铜人面具是什么”验证召回相关性，再验证高/低置信度图片路由。检查业务 code="0"、SSE 正文增量和最终正文一致，按 execution_id 核对 `search_mode=ragflow`、search_text/search_image、model 和 total 日志。当前 210 项本地测试覆盖客户端协议、候选数/重排开关、流程、诊断日志和 TTFT，不代表上述真实语料已完成验收。通过 `ragflow_result` 查看检索结果摘要；开启正文开关后可比对检索片段和最终模型请求，见 [日志说明](logging.md#检索与模型请求诊断日志)。

部署到 AgentArts 时，使用包含对应代码的镜像，设置上述运行时变量，并按 [部署指南](deployment.md) 发布或切换到对应版本后，用新 Session ID 验证。`.env` 不会进入镜像，需单独配置运行时凭据。运行时须能访问 `RAGFLOW_URL` 选定的私网或公网地址，且仍可访问分类器和模型；见 [网络配置说明](agentarts-knowledge-network.md)。

## 原始接入参考

以下同步自用户于 2026-10-12 提供的 v2.0 文档，包含新增 §11 重排开关、§12 A1 参数和性能附录；真实 Token 已替换为占位符。文档中的服务端默认值、语料规模和耗时属于提供方部署环境记录。

本工程以此前“本工程接入与验证”为准：保留异步 WebClient、现有短回答提示词、错误处理和 SSE/TTFT，不照搬下面的同步 Service/控制器示例或输出来源文档的建议。原文 keyword 的检索模式描述不能直接套用，本工程继续省略 keyword 和向量权重，核对记录见 [验证指南](verification.md#ragflow-接入验证2026-10-10)。

原文的 YAML 配置名不是本工程的环境变量名；环境变量变更需要重启，删除候选数变量会恢复本工程默认值，要省略应显式设 null。原文所称“无需重启”“删除配置即可回滚”不适用于本工程，具体映射见 [环境变量说明](environment-variables.md)。

标准 RAG 检索 API · 文旅 Agent 接入说明（Spring AI 1.0 / Java 版）

版本：v2.0（2026-10-10，基于 Spring AI 1.0 GA 官方文档 + 真实跑通的 run-20261010-105036.log）

框架：文旅 agent 已切换 Spring AI 1.0 GA + Spring Boot 3.x + Java 17+

用途：文旅/cultural-tourism agent 通过本 API 检索文物知识库，获取相关原文片段喂给 LLM 生成回答。

状态：✅ RAGFlow 端到端调通，6 个 query 全部 <1s 返回相关结果，API Token 已生成可用。

---

### 0. 核心结论：RAGFlow API 不受框架切换影响

文旅 agent 从 Python 换成 Spring AI/Java，RAGFlow 知识库检索标准 API 的提供方式 100% 不受影响。

| 层面 | 是否受影响 | 说明 |
| --- | --- | --- |
| RAGFlow /api/v1/retrieval 接口 | ❌ 不受影响 | 标准 REST，与调用方语言/框架无关 |
| 认证方式（Bearer API Token） | ❌ 不受影响 | HTTP 标准 Header |
| 请求/响应 JSON 结构 | ❌ 不受影响 | 不变 |
| 私网地址 172.16.0.154:9380 | ❌ 不受影响 | 不变 |
| 安全组 9380 放行要求 | ❌ 不受影响 | 不变 |
| 上游集成代码 | ✅ 需重写 | Python requests → Java RestClient |

换框架只是把"谁来发 HTTP 请求"从 Python requests 换成 Java RestClient/WebClient，RAGFlow 侧零改动。

---

### 1. 接口信息

| 项 | 值 |
| --- | --- |
| 方法 | POST |
| 路径 | /api/v1/retrieval |
| 完整 URL（私网，推荐） | http://172.16.0.154:9380/api/v1/retrieval |
| 认证 | Authorization: Bearer ${RAGFLOW_API_TOKEN} |
| Content-Type | application/json |

关键参数：

| 参数 | 值 | 说明 |
| --- | --- | --- |
| API_TOKEN | ${RAGFLOW_API_TOKEN} | 长期 Token，存 /root/.ragflow_apikey |
| KB_ID（dataset_id） | e611c8e0c45111f19cb7ff060c22c24e | 知识库 travel_kb（4 篇文物语料） |
| ECS 私网 IP | 172.16.0.154 | RAGFlow 所在 ECS，与 CSS（172.16.0.53）同 VPC（cn-southwest-2） |

⚠️ 凭据安全：API_TOKEN 是可访问知识库的活凭据。建议为 agent 单独生成专用 Token。所有凭据任务结束后需轮换。

---

### 2. 请求 / 响应格式

请求

    {
      "dataset_ids": ["e611c8e0c45111f19cb7ff060c22c24e"],
      "question": "兵马俑是什么时候发现的",
      "page_size": 3,
      "similarity_threshold": 0.2,
      "keyword": false,
      "rerank_id": "bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible",
      "knn_top_k": 64,
      "knn_num_candidates": 128,
      "rerank_candidates_count": 32
    }

| 字段 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| dataset_ids | ✅ | — | 知识库 ID 列表，可传多个 |
| question | ✅ | — | 用户问题，bge-m3 多语言（中英文均可） |
| page_size | ❌ | 30 | 返回 chunk 数，agent 取 3~5 |
| similarity_threshold | ❌ | 0.2 | 相似度下限 |
| keyword | ❌ | — | true=BM25；false=向量；不传=hybrid 混合（推荐） |
| rerank_id | ❌ | — | 重排模型 ID，传了走 MaaS rerank 精排；不传 = 关闭 rerank（走本地 token 相似度排序，省一次 MaaS 公网调用，降时延约 0.09s，详见 §11） |
| knn_top_k | ❌ | 1024 | kNN 向量召回数。A1 优化建议传 64（实测质量无损，语料增长后的前瞻保险，见 §12） |
| knn_num_candidates | ❌ | 2048 | kNN 候选池大小（HNSW ef_search）。A1 优化建议传 128（见 §12） |
| rerank_candidates_count | ❌ | 100 | 送 MaaS reranker 的候选数。A1 优化建议传 32（实测降时延约 0.034s，主要增益来源，见 §12） |

响应（关键字段）

    {
      "code": 0,
      "data": {
        "total": 13,
        "chunks": [
          {
            "id": "0434bb800d72ad77",
            "content": "# Terracotta Army ...（原文片段，喂 LLM 的核心）",
            "document_id": "5ee7049ec45511f19cb7ff060c22c24e",
            "document_keyword": "terracotta-army(1).md",
            "similarity": 0.6362,
            "vector_similarity": 0.7875,
            "term_similarity": 0.5714,
            "positions": [[1, 0, 0, 0, 0]]
          }
        ],
        "doc_aggs": [
          {"count": 4, "doc_id": "...", "doc_name": "changxin-palace-lamp(1).md"},
          {"count": 3, "doc_id": "...", "doc_name": "terracotta-army(1).md"}
        ]
      }
    }

| 字段 | 说明 |
| --- | --- |
| data.chunks[].content | 核心：原文片段，拼进 LLM context |
| data.chunks[].similarity | 综合相似度，可据此过滤（<0.3 丢弃） |
| data.chunks[].document_keyword | 来源文档名，做引用溯源 |
| data.chunks[].id | chunk 唯一 ID |
| data.doc_aggs | 每篇文档命中 chunk 数 |
| data.total | 总命中数 |

---

### 3. Maven 依赖（Spring AI 1.0 GA）

    <properties>
        <java.version>17</java.version>
        <spring-ai.version>1.0.0</spring-ai.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Spring Boot Web（提供 RestClient / WebClient） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Spring AI 核心（按需，见路线 A/B） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-client-chat</artifactId>
        </dependency>

        <!-- 路线 A：若用 RetrievalAugmentationAdvisor（含自定义 DocumentRetriever） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-rag</artifactId>
        </dependency>

        <!-- LLM 调用（示例用 OpenAI 兼容，换成你的实际模型 provider） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>
    </dependencies>

Spring Boot 3.2+ 与 Spring AI 1.0 GA 配套；JDK 17+。

---

### 4. 两条集成路线（按推荐度）

路线 B（推荐）：直接 HTTP 调用 + 自己拼 prompt

文旅 agent 用 Spring RestClient 直接调 RAGFlow /api/v1/retrieval，拿回 chunks，拼进 LLM prompt。最简单、最稳、最快落地，完全绕开 Spring AI RAG 抽象适配成本。

路线 A（可选增强）：Spring AI 原生 RAG + 自定义 DocumentRetriever

按官方文档 RetrievalAugmentationAdvisor.builder().documentRetriever(...)，但官方只有 VectorStoreDocumentRetriever（需直连 VectorStore），没有 RAGFlow retriever，需自己实现 DocumentRetriever 接口把 retrieve() 转成调 RAGFlow HTTP API。适合想用 Spring AI 全套 RAG 模块（query 改写、join、post-process）的团队。

---

### 5. 路线 B 代码（推荐，可直接用）

#### 5.1 配置（application.yml）

    travel-agent:
      ragflow:
        base-url: http://172.16.0.154:9380/api/v1
        api-token: ${RAGFLOW_API_TOKEN}
        kb-id: e611c8e0c45111f19cb7ff060c22c24e
        rerank-id: bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible
        # 是否启用 rerank 精排。true=开（默认）；false=关，降时延约 0.09s（详见 §11）
        use-rerank: true
        page-size: 3
        similarity-threshold: 0.2
        timeout-seconds: 10
        # A1 时延优化（请求级传参，不改源码）。实测质量无损、median 0.297→0.263s（−11.4%，详见 §12）
        # null=不传该参数=用 RAGFlow 默认值（knn_top_k=1024 等）
        knn-top-k: 64
        knn-num-candidates: 128
        rerank-candidates-count: 32

#### 5.2 配置类

    package com.example.travelagent.config;

    import org.springframework.boot.context.properties.ConfigurationProperties;
    import org.springframework.context.annotation.Configuration;

    @Configuration
    @ConfigurationProperties(prefix = "travel-agent.ragflow")
    public class RagFlowProperties {
        private String baseUrl;
        private String apiToken;
        private String kbId;
        private String rerankId;
        /** 是否启用 rerank 精排。true=开（传 rerank_id）；false=关（不传 rerank_id，降时延约 0.09s，详见 §11）。默认 true */
        private boolean useRerank = true;
        private int pageSize = 3;
        private double similarityThreshold = 0.2;
        private int timeoutSeconds = 10;
        /** A1 时延优化：kNN 向量召回数，默认 1024，建议 64。null=不传=用默认（见 §12） */
        private Integer knnTopK = 64;
        /** A1 时延优化：kNN 候选池大小，默认 2048，建议 128。null=不传=用默认（见 §12） */
        private Integer knnNumCandidates = 128;
        /** A1 时延优化：送 rerank 候选数，默认 100，建议 32。null=不传=用默认（见 §12） */
        private Integer rerankCandidatesCount = 32;

        // getters / setters 省略，请补全
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiToken() { return apiToken; }
        public void setApiToken(String apiToken) { this.apiToken = apiToken; }
        public String getKbId() { return kbId; }
        public void setKbId(String kbId) { this.kbId = kbId; }
        public String getRerankId() { return rerankId; }
        public void setRerankId(String rerankId) { this.rerankId = rerankId; }
        public boolean isUseRerank() { return useRerank; }
        public void setUseRerank(boolean useRerank) { this.useRerank = useRerank; }
        public int getPageSize() { return pageSize; }
        public void setPageSize(int pageSize) { this.pageSize = pageSize; }
        public double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public Integer getKnnTopK() { return knnTopK; }
        public void setKnnTopK(Integer knnTopK) { this.knnTopK = knnTopK; }
        public Integer getKnnNumCandidates() { return knnNumCandidates; }
        public void setKnnNumCandidates(Integer knnNumCandidates) { this.knnNumCandidates = knnNumCandidates; }
        public Integer getRerankCandidatesCount() { return rerankCandidatesCount; }
        public void setRerankCandidatesCount(Integer rerankCandidatesCount) { this.rerankCandidatesCount = rerankCandidatesCount; }
    }

#### 5.3 请求/响应 DTO

    package com.example.travelagent.ragflow.dto;

    import com.fasterxml.jackson.annotation.JsonProperty;
    import java.util.List;

    /** RAGFlow 检索请求 */
    public class RetrievalRequest {
        @JsonProperty("dataset_ids")
        private List<String> datasetIds;
        private String question;
        @JsonProperty("page_size")
        private int pageSize;
        @JsonProperty("similarity_threshold")
        private double similarityThreshold;
        /** 不传 = hybrid 混合检索（推荐）；true=BM25；false=纯向量 */
        private Boolean keyword;
        @JsonProperty("rerank_id")
        private String rerankId;
        /** A1 时延优化参数：kNN 向量召回数，默认 1024，建议 64（见 §12）。null=不传=用默认 */
        @JsonProperty("knn_top_k")
        private Integer knnTopK;
        /** A1 时延优化参数：kNN 候选池大小，默认 2048，建议 128（见 §12）。null=不传=用默认 */
        @JsonProperty("knn_num_candidates")
        private Integer knnNumCandidates;
        /** A1 时延优化参数：送 rerank 候选数，默认 100，建议 32（见 §12）。null=不传=用默认 */
        @JsonProperty("rerank_candidates_count")
        private Integer rerankCandidatesCount;

        // getters / setters 省略，请补全
        public List<String> getDatasetIds() { return datasetIds; }
        public void setDatasetIds(List<String> datasetIds) { this.datasetIds = datasetIds; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public int getPageSize() { return pageSize; }
        public void setPageSize(int pageSize) { this.pageSize = pageSize; }
        public double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(double v) { this.similarityThreshold = v; }
        public Boolean getKeyword() { return keyword; }
        public void setKeyword(Boolean k) { this.keyword = k; }
        public String getRerankId() { return rerankId; }
        public void setRerankId(String r) { this.rerankId = r; }
        public Integer getKnnTopK() { return knnTopK; }
        public void setKnnTopK(Integer knnTopK) { this.knnTopK = knnTopK; }
        public Integer getKnnNumCandidates() { return knnNumCandidates; }
        public void setKnnNumCandidates(Integer knnNumCandidates) { this.knnNumCandidates = knnNumCandidates; }
        public Integer getRerankCandidatesCount() { return rerankCandidatesCount; }
        public void setRerankCandidatesCount(Integer rerankCandidatesCount) { this.rerankCandidatesCount = rerankCandidatesCount; }
    }

    package com.example.travelagent.ragflow.dto;

    import com.fasterxml.jackson.annotation.JsonProperty;
    import java.util.List;

    /** RAGFlow 检索响应 */
    public class RetrievalResponse {
        private int code;
        private Data data;

        public int getCode() { return code; }
        public void setCode(int code) { this.code = code; }
        public Data getData() { return data; }
        public void setData(Data data) { this.data = data; }

        public static class Data {
            private int total;
            private List<Chunk> chunks;
            @JsonProperty("doc_aggs")
            private List<DocAgg> docAggs;
            // getters / setters 补全
            public int getTotal() { return total; }
            public void setTotal(int t) { this.total = t; }
            public List<Chunk> getChunks() { return chunks; }
            public void setChunks(List<Chunk> c) { this.chunks = c; }
            public List<DocAgg> getDocAggs() { return docAggs; }
            public void setDocAggs(List<DocAgg> d) { this.docAggs = d; }
        }

        public static class Chunk {
            private String id;
            private String content;
            @JsonProperty("document_id")
            private String documentId;
            @JsonProperty("document_keyword")
            private String documentKeyword;
            private double similarity;
            @JsonProperty("vector_similarity")
            private double vectorSimilarity;
            @JsonProperty("term_similarity")
            private double termSimilarity;
            // getters / setters 补全
            public String getId() { return id; }
            public void setId(String i) { this.id = i; }
            public String getContent() { return content; }
            public void setContent(String c) { this.content = c; }
            public String getDocumentId() { return documentId; }
            public void setDocumentId(String d) { this.documentId = d; }
            public String getDocumentKeyword() { return documentKeyword; }
            public void setDocumentKeyword(String d) { this.documentKeyword = d; }
            public double getSimilarity() { return similarity; }
            public void setSimilarity(double s) { this.similarity = s; }
            public double getVectorSimilarity() { return vectorSimilarity; }
            public void setVectorSimilarity(double v) { this.vectorSimilarity = v; }
            public double getTermSimilarity() { return termSimilarity; }
            public void setTermSimilarity(double t) { this.termSimilarity = t; }
        }

        public static class DocAgg {
            private int count;
            @JsonProperty("doc_id")
            private String docId;
            @JsonProperty("doc_name")
            private String docName;
            // getters / setters 补全
        }
    }

#### 5.4 Service（核心检索逻辑）

    package com.example.travelagent.ragflow;

    import com.example.travelagent.config.RagFlowProperties;
    import com.example.travelagent.ragflow.dto.*;
    import org.springframework.stereotype.Service;
    import org.springframework.web.client.RestClient;

    import java.time.Duration;
    import java.util.ArrayList;
    import java.util.List;

    @Service
    public class RagFlowRetrievalService {

        private final RagFlowProperties props;
        private final RestClient restClient;

        public RagFlowRetrievalService(RagFlowProperties props) {
            this.props = props;
            this.restClient = RestClient.builder()
                    .baseUrl(props.getBaseUrl())
                    .defaultHeader("Authorization", "Bearer " + props.getApiToken())
                    .defaultHeader("Content-Type", "application/json")
                    .build();
        }

        /** 检索知识库，返回最相关的 chunks（hybrid + rerank） */
        public List<RetrievalResponse.Chunk> retrieve(String question) {
            return retrieve(question, props.getPageSize());
        }

        public List<RetrievalResponse.Chunk> retrieve(String question, int topK) {
            RetrievalRequest req = new RetrievalRequest();
            req.setDatasetIds(List.of(props.getKbId()));
            req.setQuestion(question);
            req.setPageSize(topK);
            req.setSimilarityThreshold(props.getSimilarityThreshold());
            // keyword 不设 = hybrid 混合检索（推荐）
            req.setKeyword(null);
            // useRerank=false 时不传 rerank_id → 关闭 MaaS rerank，走本地 token 相似度排序
            // 省一次 MaaS 公网调用，端到端降时延约 0.09s（详见 §11）
            req.setRerankId(props.isUseRerank() ? props.getRerankId() : null);
            // A1 时延优化（请求级传参，不改源码）：缩小 kNN 池 + rerank 候选数
            // 实测质量无损，median 0.297→0.263s（−11.4%）。null=不传=用 RAGFlow 默认（详见 §12）
            req.setKnnTopK(props.getKnnTopK());
            req.setKnnNumCandidates(props.getKnnNumCandidates());
            // rerank_candidates_count 仅在开 rerank 时有意义
            if (props.isUseRerank() && props.getRerankCandidatesCount() != null) {
                req.setRerankCandidatesCount(props.getRerankCandidatesCount());
            }

            RetrievalResponse resp = restClient.post()
                    .uri("/retrieval")
                    .body(req)
                    .retrieve()
                    .body(RetrievalResponse.class);

            if (resp == null || resp.getCode() != 0 || resp.getData() == null) {
                return List.of();
            }
            return resp.getData().getChunks() == null ? List.of() : resp.getData().getChunks();
        }

        /** 拼 context 文本 + 来源列表 */
        public RetrievalContext buildContext(String question) {
            return buildContext(question, props.getPageSize());
        }

        public RetrievalContext buildContext(String question, int topK) {
            List<RetrievalResponse.Chunk> chunks = retrieve(question, topK);
            // 低质过滤
            List<RetrievalResponse.Chunk> filtered = new ArrayList<>();
            List<String> sources = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < chunks.size(); i++) {
                RetrievalResponse.Chunk c = chunks.get(i);
                if (c.getSimilarity() < 0.3) continue;   // 低质丢弃
                if (i > 0) sb.append("\n\n---\n\n");
                sb.append(c.getContent());
                sources.add(c.getDocumentKeyword());
                filtered.add(c);
            }
            return new RetrievalContext(sb.toString(), sources, filtered);
        }

        /** 检索结果封装 */
        public record RetrievalContext(
                String context,
                List<String> sources,
                List<RetrievalResponse.Chunk> chunks) {}
    }

#### 5.5 文旅 Agent 调用 LLM（拼 prompt）

    package com.example.travelagent.agent;

    import com.example.travelagent.ragflow.RagFlowRetrievalService;
    import com.example.travelagent.ragflow.RagFlowRetrievalService.RetrievalContext;
    import org.springframework.ai.chat.client.ChatClient;
    import org.springframework.stereotype.Service;

    import java.util.List;

    @Service
    public class TravelAgentService {

        private final ChatClient chatClient;
        private final RagFlowRetrievalService ragService;

        private static final String SYSTEM_PROMPT = """
                你是文旅讲解助手。根据以下博物馆资料回答问题。
                只依据资料作答，不要编造；引用时标注来源文档。
                如果资料中没有相关内容，请说明"资料中未提及"。
                """;

        public TravelAgentService(ChatClient.Builder chatClientBuilder,
                                  RagFlowRetrievalService ragService) {
            this.chatClient = chatClientBuilder.build();
            this.ragService = ragService;
        }

        public String answer(String question) {
            // 1. 检索 RAGFlow 知识库
            RetrievalContext ctx = ragService.buildContext(question, 3);

            // 2. 拼 prompt 喂 LLM
            String prompt = SYSTEM_PROMPT + "\n\n资料:\n" + ctx.context()
                    + "\n\n来源文档: " + String.join(", ", ctx.sources())
                    + "\n\n问题: " + question;

            // 3. LLM 生成
            return chatClient.prompt().user(prompt).call().content();
        }
    }

#### 5.6 可选：REST 控制器（供 AgentArts 运行时 /invocations）

    package com.example.travelagent.controller;

    import com.example.travelagent.agent.TravelAgentService;
    import org.springframework.web.bind.annotation.*;

    import java.util.Map;

    @RestController
    @RequestMapping("/invocations")   // AgentArts 运行时标准入站协议
    public class TravelAgentController {

        private final TravelAgentService agentService;

        public TravelAgentController(TravelAgentService agentService) {
            this.agentService = agentService;
        }

        @PostMapping
        public Map<String, String> invoke(@RequestBody Map<String, String> body) {
            String question = body.getOrDefault("question", "");
            String answer = agentService.answer(question);
            return Map.of("answer", answer);
        }
    }

AgentArts 运行时要求镜像提供 POST /invocations 端点（见运行时文档）。上面控制器直接对齐该协议。

---

### 6. 路线 A 代码（可选：Spring AI 原生 RAG）

若团队想用 Spring AI 全套 RAG 模块（query 改写/重排/join），可自定义 DocumentRetriever 适配 RAGFlow。

    package com.example.travelagent.rag;

    import com.example.travelagent.config.RagFlowProperties;
    import com.example.travelagent.ragflow.RagFlowRetrievalService;
    import com.example.travelagent.ragflow.dto.RetrievalResponse;
    import org.springframework.ai.document.Document;
    import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
    import org.springframework.ai.rag.Query;

    import java.util.HashMap;
    import java.util.List;
    import java.util.Map;

    /** 自定义 DocumentRetriever：把 Spring AI 的 retrieve() 转成调 RAGFlow HTTP API */
    public class RagFlowDocumentRetriever implements DocumentRetriever {

        private final RagFlowRetrievalService ragService;

        public RagFlowDocumentRetriever(RagFlowRetrievalService ragService) {
            this.ragService = ragService;
        }

        @Override
        public List<Document> retrieve(Query query) {
            String question = query.getText();
            List<RetrievalResponse.Chunk> chunks = ragService.retrieve(question);

            // 把 RAGFlow chunk 转成 Spring AI Document
            return chunks.stream()
                    .map(c -> {
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("source", c.getDocumentKeyword());
                        meta.put("similarity", c.getSimilarity());
                        meta.put("doc_id", c.getDocumentId());
                        return new Document(c.getContent(), meta);
                    })
                    .toList();
        }
    }

    package com.example.travelagent.rag;

    import com.example.travelagent.config.RagFlowProperties;
    import com.example.travelagent.ragflow.RagFlowRetrievalService;
    import org.springframework.ai.chat.client.ChatClient;
    import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
    import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
    import org.springframework.context.annotation.Bean;
    import org.springframework.context.annotation.Configuration;

    @Configuration
    public class RagFlowRagConfig {

        @Bean
        public RetrievalAugmentationAdvisor ragFlowAdvisor(
                RagFlowRetrievalService ragService) {
            return RetrievalAugmentationAdvisor.builder()
                    .documentRetriever(new RagFlowDocumentRetriever(ragService))
                    .queryAugmenter(ContextualQueryAugmenter.builder()
                            .allowEmptyContext(true)
                            .build())
                    .build();
        }
    }

    // 使用
    @Service
    public class TravelAgentRagService {
        private final ChatClient chatClient;
        private final RetrievalAugmentationAdvisor advisor;

        public TravelAgentRagService(ChatClient.Builder builder, RetrievalAugmentationAdvisor advisor) {
            this.chatClient = builder.build();
            this.advisor = advisor;
        }

        public String answer(String question) {
            return chatClient.prompt()
                    .user(question)
                    .advisors(advisor)
                    .call()
                    .content();
        }
    }

路线 A 把检索解耦进 advisor，Spring AI 自动把检索到的 Document 拼进 prompt。但需注意：Spring AI 的 DocumentRetriever 接口签名以官方文档为准（上面基于官方文档 1.0 retrieve(Query) 实现）。集成时若编译不匹配，以官方 spring-ai-rag jar 的实际签名为准。

---

### 7. 可达性验证（已通过 ✅）

2026-10-10 14:20 在 ECS 上（同 VPC，走 172.16.0.154 网卡）实测通过（run-20261010-142020.log）：

| 测试 | 结果 | 含义 |
| --- | --- | --- |
| T1 healthz（无认证） | http_code=200 6ms + {"status":"ok","db":"ok","doc_engine":"ok",...} | RAGFlow 健康，内部组件全 ok（doc_engine=CSS OpenSearch 连接正常） |
| T2 retrieval（带认证） | http_code=200 0.76s + {"code":0,"data":{"chunks":[...]}} | 检索成功，返回兵马俑 chunks |
| T3 解析 | code=0 total=3 + 3 条 sim 0.63–0.69 的 terracotta-army chunk | 检索内容正确，相关度正常 |

结论：同 VPC 私网完全可达。 AgentArts 运行时只要和 RAGFlow 同 VPC（子网在 172.16.0.0/23 放通范围），即 http_code=200 + code:0，可直接对接，无需再做网络验证。

注：本地（不在该 VPC）实测会返回 504 + HIS Proxy / NetentSec HTML——那是企业出口安全网关（SWG）拦截页，不是 RAGFlow 响应，与同 VPC 路径无关。同 VPC 调用走内网直连，不经过 SWG。

复测脚本（若需复验）

在 ECS 上（同 VPC）跑，纯 ASCII，已分包传输（见 scripts/rag-vpc-check.sh）。

#### 7.1 ECS 上验证脚本（纯 ASCII，用 rlog）

    rlog "curl -s -o /tmp/rag_test -w 'http_code=%{http_code} time=%{time_total}s\n' \
      -X POST http://172.16.0.154:9380/api/v1/retrieval \
      -H 'Authorization: Bearer ${RAGFLOW_API_TOKEN}' \
      -H 'Content-Type: application/json' \
      -d '{\"dataset_ids\":[\"e611c8e0c45111f19cb7ff060c22c24e\"],\"question\":\"terracotta army\",\"page_size\":3,\"rerank_id\":\"bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible\"}'"
    rlog "python3 -c \"import json;d=json.load(open('/tmp/rag_test'));print('code=',d['code'],'total=',d['data']['total']);[print('  sim=%.4f'%c['similarity'],c['document_keyword'][:40]) for c in d['data']['chunks'][:3]]\""

预期：http_code=200、code= 0、total= 13、打印 3 条 sim>0.6 的 chunk。

#### 7.2 Java 侧连通性自检（运行时启动后）

在 agent 运行时内（或本地用公网/VPCE 验证）调用 RagFlowRetrievalService.retrieve("terracotta army")，断言返回非空且 similarity > 0.6 即通。

---

### 8. 网络访问方式（AgentArts 运行时私网对接）

推荐：AgentArts 运行时私网访问

AgentArts 运行时部署托管时选私网访问，经 VPC 内网直接调 RAGFlow。

私网访问地址： http://172.16.0.154:9380/api/v1/retrieval

- 端口固定 9380（RAGFlow v0.27.2 Python 后端）。
- ECS 私网 IP 172.16.0.154，与 CSS（172.16.0.53）同在 cn-southwest-2 同一 VPC。

#### 8.1 关键前提——ECS 安全组放行 9380

| 配置项 | 值 |
| --- | --- |
| 方向 | 入方向（Inbound） |
| 协议/端口 | TCP / 9380 |
| 源地址 | AgentArts 运行时所在 VPC/子网网段 |
| 描述 | AgentArts 文旅 agent 运行时访问 RAGFlow |

源地址获取：在 AgentArts 控制台部署运行时、选定"私网访问"后，从控制台/运行时详情页确认运行时所在 VPC ID 与子网 CIDR，据此在 ECS 安全组入方向放行该网段访问 TCP 9380。

#### 8.2 两步行动清单

### 1. AgentArts 控制台：部署文旅 agent 运行时 → 选"私网访问" → 记录运行时 VPC/子网网段。
### 2. 华为云控制台：ECS 安全组 → 入方向规则 → 添加：TCP 9380，源地址 = 运行时子网网段。
### 3. 运行时内验证：curl http://172.16.0.154:9380/api/v1/retrieval ...（见 §7.1），返回 code:0 即通。

#### 8.3 备选：公网访问（不推荐）

    http://<ECS公网EIP>:9380/api/v1/retrieval

- 需先给 ECS 绑 EIP + 安全组入方向放行 9380。
- ⚠️ RAGFlow 9380 是明文 HTTP，API Token 走公网明文 = 安全风险。仅当私网确不可达时作兜底，并前置加 HTTPS 反代/网关。

当前 ECS 只有 SNAT 出网、无 EIP。私网访问是推荐路径，免 EIP、更安全、时延更低。

---

### 9. 文旅 Agent 集成建议

### 1. 推荐路线 B（直接 HTTP），1 天内可落地；路线 A（Spring AI 原生 RAG）按需选。
### 2. 检索参数：page_size=3~5、similarity_threshold=0.2、不传 keyword（hybrid）、传 rerank_id（精排）。
### 3. 低质过滤：返回 chunk similarity < 0.3 丢弃，避免噪声进 LLM。
### 4. 引用溯源：回答带 document_keyword（兵马俑/长信宫灯/唐三彩骆驼/青铜面具）。
### 5. 多语言：bge-m3 多语言，中文问英文库可召回（实测通过）。
### 6. 超时：RestClient 设 10s（实测 <1s）。
### 7. 重试：网络抖动重试 2 次。
### 8. AgentArts 镜像约束：镜像须 ARM64（运行时要求），监听 8080，提供 POST /invocations（见 §5.6 控制器）。

---

### 10. 错误码

| code | 含义 | 处理 |
| --- | --- | --- |
| 0 | 成功 | — |
| 401 | Token 失效/过期 | 检查 API_TOKEN，必要时重新生成 |
| 400 | 参数错误 | 检查 dataset_ids、question 非空 |
| 5xx | 服务异常 | 联系部署同学查容器日志 |

---

### 11. 降低时延：关闭 rerank（不传 rerank_id）

#### 11.1 背景与原理

RAGFlow /api/v1/retrieval 的端到端处理分为 5 个阶段：

    ① embedding（MaaS）→ ② kNN+BM25 检索（CSS OpenSearch）→ ③ token 相似度排序（本地）
    → ④ rerank 精排（MaaS）→ ⑤ 聚合返回（本地）

其中第 ④ 步 rerank 精排 会发起一次到 MaaS bge-reranker-v2-m3 的公网调用。该调用是端到端时延中一个可观的固定开销。

RAGFlow 的 rerank 是按需触发的，机制如下（chunk_api.py）：

    rerank_mdl = None
    if req.get("rerank_id"):
        rerank_mdl = LLMBundle(...)   # 仅当请求携带 rerank_id 时才实例化 rerank 模型

后续检索逻辑（search.py）：

    if rerank_mdl and sres.total > 0:
        rerank_by_model(...)          # 有 rerank_mdl 才做精排；为 None 则直接跳过

结论：请求中不传 rerank_id，即等价于关闭 rerank 精排，端到端少走一次 MaaS 公网调用。这条路径 RAGFlow 原生支持，无需修改任何源码或后端配置。

#### 11.2 为什么要关闭 rerank

| 维度 | 开 rerank（传 rerank_id） | 关 rerank（不传 rerank_id） |
| --- | --- | --- |
| 处理阶段 | ① ② ③ ④ ⑤ | ① ② ③ ⑤（跳过 ④） |
| MaaS 公网调用 | 2 次（embedding + rerank） | 1 次（仅 embedding） |
| 排序依据 | MaaS rerank 精排 | 本地 token 相似度排序 |
| 预期时延 | 基准 | 约低 0.09s（省一次 MaaS 往返） |
| 召回质量 | 高（交叉编码精排） | 略降（依赖 kNN 召回质量） |

- 降低时延是关闭 rerank 的主要目的。对于文旅问答这类召回候选较少、对首字时延敏感的场景，省掉一次 MaaS 往返能带来可感知的提速。
- 质量取舍：rerank 对最终排序有正向作用，关闭后排序质量取决于 kNN 向量召回 + 本地 token 相似度。对当前 4 篇文物语料（共 14 个 chunk）的小库，kNN 召回已足够精确，关闭 rerank 的质量损失在可接受范围内；语料规模扩大后建议重新评估是否恢复 rerank。

#### 11.3 如何关闭 rerank

本接入文档提供的 Spring AI 客户端封装了 useRerank 开关（见 §5.1、§5.2、§5.4），有两种使用方式：

方式 A：配置开关（推荐，改配置不改代码）

在 application.yml 中将 use-rerank 设为 false：

    travel-agent:
      ragflow:
        base-url: http://172.16.0.154:9380/api/v1
        api-token: <your-api-token>
        kb-id: e611c8e0c45111f19cb7ff060c22c24e
        rerank-id: bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible
        use-rerank: false        # ← false：不传 rerank_id，关闭精排降时延；true：开启（默认）
        page-size: 3
        similarity-threshold: 0.2
        timeout-seconds: 10

RagFlowProperties 读取该配置，RagFlowRetrievalService.retrieve() 中：

    // useRerank=false 时不传 rerank_id → 关闭 MaaS rerank，走本地 token 相似度排序
    // 省一次 MaaS 公网调用，端到端降时延约 0.09s
    req.setRerankId(props.isUseRerank() ? props.getRerankId() : null);

注：rerank-id 配置项仍需保留（恢复 rerank 时无需重新填入），只是在 use-rerank: false 时不参与请求。

方式 B：请求级控制（不依赖全局开关）

若希望大部分请求开 rerank、个别请求关 rerank，可直接调用底层 RagFlowRetrievalService 并显式置空 rerankId：

    RetrievalRequest req = new RetrievalRequest();
    req.setDatasetIds(List.of("e611c8e0c45111f19cb7ff060c22c24e"));
    req.setQuestion("兵马俑");
    req.setRerankId(null);        // ← 显式不传，本次请求关闭 rerank
    req.setPageSize(3);
    req.setSimilarityThreshold(0.2f);

无论方式 A 还是方式 B，最终对 RAGFlow 的效果一致：请求 body 中不带 rerank_id 字段。

#### 11.4 实测时延对比

以下数据取自当前 4 篇文物语料（共 14 个 chunk）的演示知识库，每项跑 10 次取中位数：

| 场景 | 调用方式 | 中位时延 | 说明 |
| --- | --- | --- | --- |
| T1 端到端（开 rerank） | 传 rerank_id | 0.295s | 走完整 5 阶段，含 MaaS rerank |
| T2 向量检索（关 rerank） | 不传 rerank_id | 0.204s | 跳过第 ④ 步 |
| 差值 | — | ≈ 0.09s | 即一次 MaaS rerank 往返的固定开销 |

即：在当前小库上，关闭 rerank 可将端到端时延从约 0.29s 降至约 0.20s，降幅约 30%。

#### 11.5 注意事项与待测试项

### 1. 实测数据基于演示库：上述 0.09s 的差值是在 14-chunk 演示库上测得的，MaaS rerank 的调用时延以网络往返为主、与库规模基本无关，因此在正式语料上该差值预计仍成立。但正式上线后建议以真实流量复测一次，以确认：
   - 关闭 rerank 后的排序质量是否满足文旅问答的准确率要求；
   - 实际降时延幅度是否与演示库一致。
### 2. 关闭 rerank 后的兜底排序：不传 rerank_id 时，RAGFlow 退回本地 token 相似度排序（第 ③ 步），结果仍按相似度降序返回，page-size / similarity-threshold 等参数照常生效。
### 3. 恢复 rerank：将 use-rerank 改回 true（或请求中恢复 rerank_id）即可，无需重启或改源码。
### 4. use-rerank 默认 true：即开箱即用保持原有行为（开 rerank），仅在显式关闭时才降时延，避免影响既有调用方。

---

### 12. 降低时延：A1 路径1（缩小 kNN 池 + rerank 候选数，请求级传参）

#### 12.1 背景与原理

RAGFlow /api/v1/retrieval 默认 knn_top_k=1024、knn_num_candidates=2048、rerank_candidates_count=100。这套默认值面向万级 chunk 的大库设计，对当前 56-chunk 的文旅库明显过大：

- kNN 每次都按 1024 的池去搜（实际库只有 56 个 chunk，等同全库扫描）；
- 把过多候选送 MaaS rerank，增加不必要的公网往返。

A1 路径1 不改源码，仅通过请求级传参把这 3 个值缩到适配当前库的量级：knn_top_k=64 / knn_num_candidates=128 / rerank_candidates_count=32。

#### 12.2 实测结论（中文库 56 chunks，run-20261010-191913.log，N=8）

召回质量 —— 质量无损 ✅

| query | k=1024（默认） | k=64（A1） | 对比 |
| --- | --- | --- | --- |
| 兵马俑怎么做的（开 rerank） | D1 Top3 | A1k Top3 | chunk 与顺序完全一致 |
| 兵马俑怎么做的（关 rerank） | D2 Top3 | A1n Top3 | chunk 与顺序完全一致 |
| 长信宫灯（开 rerank） | L1 Top3 | L1A Top3（3 次复核均正常） | 一致 |

端到端时延（query=兵马俑怎么做的，N=8）

| 配置 | min | median | avg | vs 基线 |
| --- | --- | --- | --- | --- |
| D1 默认 k=1024 + rerank（基线） | 0.286 | 0.297 | 0.305 | — |
| D2 默认 k=1024 关 rerank | 0.274 | 0.278 | 0.280 | −6.4% |
| A1k A1 参数 k=64 + rerank | 0.256 | 0.263 | 0.272 | −11.4%（−34ms） ✅ |
| A1n A1 参数 k=64 关 rerank | 0.270 | 0.279 | 0.283 | −6.1% |

最优组合 = A1k（k=64 + rerank_candidates_count=32 + 开 rerank）= median 0.263s，比基线降 34ms。

#### 12.3 关键洞察：34ms 主要来自 rerank_candidates_count=32

- 关 rerank 时，k 缩小无收益：A1n(k=64) 0.279 ≈ D2(k=1024) 0.278（+1ms，噪声）——56 chunk 下 kNN 遍历被 CSS 插件固定开销主导，k 缩小是时延中性的。
- 开 rerank 时，k 缩小有收益：A1k(k=64) 0.263 vs D1(k=1024) 0.297 = −34ms——这 34ms 实际来自 rerank_candidates_count 从默认缩到 32，减少了送 MaaS reranker 的候选数。

结论：rerank_candidates_count=32 是 A1 时延增益的主要来源；knn_top_k=64 / knn_num_candidates=128 在 56 chunk 下时延中性，但作为语料增长后的前瞻性保险（避免大库时全库扫描开销），一并无害且质量无损，故同时推荐。

#### 12.4 如何启用

方式 A：配置开关（推荐，改 application.yml）

§5.1 已内置这 3 个参数，默认值即 A1 推荐值。null 表示不传该参数、用 RAGFlow 默认值：

    travel-agent:
      ragflow:
        knn-top-k: 64            # 默认 1024，A1 推荐 64；null=用默认
        knn-num-candidates: 128  # 默认 2048，A1 推荐 128；null=用默认
        rerank-candidates-count: 32  # 默认 100，A1 推荐 32；null=用默认

§5.4 retrieve() 已自动读取并注入请求，rerank_candidates_count 仅在 use-rerank: true 时发送。

方式 B：请求级临时传参（验证/灰度）

    curl -X POST "$BASE/retrieval" -H "Authorization: Bearer $TOK" \
      -H "Content-Type: application/json" \
      -d '{"dataset_ids":["e611c8e0c45111f19cb7ff060c22c24e"],"question":"兵马俑怎么做的",
           "page_size":3,"rerank_id":"bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible",
           "knn_top_k":64,"knn_num_candidates":128,"rerank_candidates_count":32}'

#### 12.5 回滚与注意事项

### 1. 零改源码、零风险回滚：这 3 个参数是 RAGFlow 原生请求字段（chunk_api.py:438-458 接收），不传即用默认。回滚只需把 yml 值改为 null 或删除对应行。
### 2. 质量无损已实测：中文库 56 chunks 上，k=1024 与 k=64 的 Top3 召回（含顺序）完全一致。
### 3. A1 与 §11 rerank-off 可叠加：A1k（开 rerank + 小候选）和 A1n（关 rerank + 小候选）可二选一。若首字时延最关键且可接受略降精排质量，选 A1n（关 rerank）；若排序质量优先，选 A1k（开 rerank + 缩候选）——后者已含 A1 的全部时延增益且保精排。
### 4. 语料规模增长后需复测：当前 A1 收益在 56 chunk 下测得。当 chunk 数增长到数百/千级，knn_top_k=64 会开始真正限制召回（不再是"中性保险"），届时需按真实规模上调 knn_top_k 并复测召回率。

---

### 附录：实测时延（run-20261010-105036.log）

| query | 时延 | 命中文档 |
| --- | --- | --- |
| terracotta | 0.46s | 兵马俑 |
| army | 0.37s | 兵马俑 |
| lamp | 0.26s | 长信宫灯 |
| camel | 0.29s | 唐三彩骆驼 |
| bronze | 0.27s | 青铜面具 |
| mask | 0.74s | 青铜面具 |

全部 <1s。注：此为 RAGFlow 内部处理时延（MaaS embedding + CSS kNN + MaaS rerank），不含 agent↔RAGFlow 网络跳（私网约 2–6ms）。

---

### 附录：知识库内容（travel_kb，4 篇文物语料）

| 文档 | 对应文物 |
| --- | --- |
| terracotta-army.md | 秦始皇兵马俑 |
| changxin-palace-lamp.md | 长信宫灯 |
| tang-camel-musicians.md | 唐三彩载乐骆驼俑 |
| bronze-face-mask.md | 商青铜人面具（三星堆） |

---

### 附录：端到端时延与分段时延实测总结

本附录汇总 RAGFlow /api/v1/retrieval 的实测时延，覆盖端到端时延与分段时延两部分。§A.1–A.6 数据取自 14-chunk 阶段；A.7 为 56-chunk 中文语料库（已重新切分）上的 A1 路径1 验证结果。

#### A.1 测试方法

- 测量工具：curl -w '%{time_total}'，每项连续跑 N=10 次，统计 min / median / avg。
- 取值口径：以 median（中位数） 为主要参考——它能抵抗偶发抖动；avg 受单次尖峰（如 MaaS 公网偶发慢请求）拉高明显，仅作辅助；min 反映无抖动下的下限。
- 测试环境：ECS（172.16.0.154）本机直连 RAGFlow（9380）→ CSS OpenSearch（172.16.0.53:9200，kNN 开源引擎）→ MaaS（公网）。测得的是 RAGFlow 内部处理时延，不含 agent↔RAGFlow 这一跳（私网约 2–6ms）。

#### A.2 探针定义

通过控制 rerank_id 与 keyword 两个参数，构造 6 组探针，分别命中不同处理阶段：

| 探针 | 请求参数 | 命中阶段 | 用途 |
| --- | --- | --- | --- |
| A | 直连 MaaS embedding | ① embedding | 测①单独耗时 |
| T1 | rerank_id 传入 | ①②③④⑤（全流程） | 端到端开 rerank |
| T2 | keyword:false（纯向量） | ①②③⑤（跳过④） | 端到端关 rerank |
| T2b | keyword:true（纯 BM25） | ①②(BM25)③⑤ | 测纯关键词路径 |
| T3 | rerank_id+小召回池 | ①②③④⑤ | 测缩小 kNN 召回池影响 |
| T4 | keyword:false+小召回池 | ①②③⑤ | T2 的小召回池对照 |

阶段映射：① embedding(MaaS) → ② kNN+BM25(CSS OpenSearch) → ③ token 相似度(本地) → ④ rerank(MaaS) → ⑤ 聚合(本地)。

#### A.3 端到端实测结果（当前配置，N=10）

当前线上配置（H1+H2 后：shards=1 + knn method 块），run-20261010-171013.log：

| 探针 | query | min | median | avg |
| --- | --- | --- | --- | --- |
| A | terracotta | 0.019 | 0.023 | 0.075 |
| T1 端到端（开 rerank） | terracotta | 0.252 | 0.295 | 0.509 |
| T1 端到端（开 rerank） | lamp | 0.257 | 0.288 | 0.293 |
| T2 端到端（关 rerank） | terracotta | 0.151 | 0.204 | 0.295 |
| T2 端到端（关 rerank） | lamp | 0.194 | 0.224 | 0.285 |
| T2b 纯 BM25 | terracotta | 0.013 | 0.014 | 0.015 |
| T2b 纯 BM25 | lamp | 0.013 | 0.013 | 0.013 |
| T3 小池+rerank | terracotta | 0.260 | 0.271 | 0.284 |
| T3 小池+rerank | lamp | 0.259 | 0.282 | 0.299 |
| T4 小池+无 rerank | terracotta | 0.185 | 0.204 | 0.290 |
| T4 小池+无 rerank | lamp | 0.195 | 0.224 | 0.315 |

端到端结论（median）：

- 开 rerank（T1）：约 0.29s（0.295 / 0.288）
- 关 rerank（T2）：约 0.21s（0.204 / 0.224）
- 两者均 远低于 1s，满足文旅 agent 首字时延要求；关闭 rerank 后端到端降约 0.07–0.09s（降幅约 25%–30%）。

#### A.4 分段时延分解

按 T2−A ≈ ②③⑤、T1−T2 ≈ ④ rerank、A ≈ ① 分解（取 median，terracotta 为代表 query）：

| 阶段 | 推算方式 | median 时延 | 占端到端比 | 说明 |
| --- | --- | --- | --- | --- |
| ① MaaS embedding | = A | 0.023s | ~8% | 公网往返 1 次 |
| ② OpenSearch kNN 检索（+③⑤ 本地） | = T2 − A | 0.181s | ~61% | 检索主力开销 |
| ④ MaaS rerank 精排 | = T1 − T2 | 0.091s | ~31% | 公网往返 1 次 |
| 端到端合计 | = T1 | 0.295s | 100% | 开 rerank 全流程 |

注：③ token 相似度排序、⑤ 聚合均为本地 Python 计算，耗时在毫秒级，已并入 ② 项内；T2b（纯 BM25）仅 0.014s，印证 ② 项的 ~0.18s 几乎全部来自 向量 kNN 检索路径，BM25 本身近乎免费。

分段结论：

### 1. ② 向量 kNN 检索是最大头（~61%，~0.18s），但经实测这部分主要是 OpenSearch 的固定开销（TLS 握手、查询解析、向量反序列化、HNSW 遍历），在 14-chunk 小库上 HNSW 图遍历本身是微秒级、不可通过参数调优压缩（见 A.5）。
### 2. ④ rerank 是可操作的杠杆（~31%，~0.09s）：不传 rerank_id 即可整段省去，端到端从 ~0.29s 降到 ~0.21s（详见 §11）。
### 3. ① embedding 占比小（~8%），且为 MaaS 公网必经，无明显优化空间。

#### A.5 H1/H2（shards=1 + knn method 块）实测对比

为压缩 ② 阶段，曾尝试 H1（索引分片 2→1）+H2（显式 knn method 块：lucene engine、ef_construction=64、m=16）。两轮实测对比（median，terracotta）：

| 探针 | 优化前（151713） | 优化后（171013） | 变化 |
| --- | --- | --- | --- |
| T1 端到端（开 rerank） | 0.261 | 0.295 | +13%（劣化） |
| T2 端到端（关 rerank） | 0.198 | 0.204 | +3%（劣化） |

结论：H1/H2 无正向收益，反小幅劣化。 原因：14-chunk 小库上 kNN 图遍历是微秒级，② 阶段 ~0.18s 几乎全是 OpenSearch 固定开销，与分片数/HNSW 参数无关；ef_construction=64 反而略降图质量。该改动对数据无害、对扩库无碍，已建议保留不回退（回退仅多一次重建，无收益）。

#### A.6 综合结论与可操作建议

| 维度 | 结论 |
| --- | --- |
| 端到端时延 | 开 rerank ~0.29s / 关 rerank ~0.21s，均 <1s，达标 |
| 时延大头 | ② 向量 kNN 检索 ~0.18s（~61%），为 OpenSearch 固定开销，小库不可调优 |
| 可操作杠杆 | ④ rerank 开关：不传 rerank_id 降 ~0.07–0.09s（降幅 ~25%–30%），见 §11 |
| 无效尝试 | H1（shards=1）、H2（knn method 块）对小库无收益，已证伪 |
| 后续待验证 | 正式语料上线后，以真实流量复测：① 关 rerank 的降时延幅度是否仍 ~0.09s；② 排序质量是否满足文旅问答准确率；③ 库规模扩大后 ② 阶段是否成为需治理的新瓶颈 |

测量脚本：scripts/rag-latency.sh（N=10 完整版）、scripts/rag_lat_min.sh（N=3 快速版）。结果日志：logs/run-20261010-151713.log（优化前基线）、logs/run-20261010-171013.log（当前配置）。

#### A.7 A1 路径1 验证结果（56-chunk 中文库，run-20261010-191913.log）

语料重新切分为中文库后 chunk 数 14→56（docs.count=56, store.size=452.7kb）。在此库上验证 A1 路径1（请求级传参缩小 kNN 池 + rerank 候选数，不改源码），N=8：

端到端时延（query=兵马俑怎么做的）

| 配置 | min | median | avg | vs 基线 |
| --- | --- | --- | --- | --- |
| D1 默认 k=1024 + rerank（基线） | 0.286 | 0.297 | 0.305 | — |
| D2 默认 k=1024 关 rerank | 0.274 | 0.278 | 0.280 | −6.4% |
| A1k A1 参数 k=64 + rerank | 0.256 | 0.263 | 0.272 | −11.4%（−34ms） ✅ |
| A1n A1 参数 k=64 关 rerank | 0.270 | 0.279 | 0.283 | −6.1% |

召回质量 —— 质量无损 ✅：k=1024 与 k=64 的 Top3 召回（含顺序）完全一致（D1≡A1k、D2≡A1n、L1≡L1A 均一致）。

关键洞察：34ms 时延增益主要来自 rerank_candidates_count=32（减少送 MaaS reranker 的候选数），而非 k 缩小——56 chunk 下 kNN 遍历仍被 CSS 插件固定开销主导，k=64 vs k=1024 时延中性（关 rerank 时 A1n≈D2）。knn_top_k=64 作为语料增长后的前瞻性保险一并推荐。详见 §12。

验证脚本：scripts/path1-verify.sh（纯 printf，无 python3/heredoc，base64 分段传输）。复核日志：logs/run-20261010-193307.log（L1A 3 次复核均正常）。
