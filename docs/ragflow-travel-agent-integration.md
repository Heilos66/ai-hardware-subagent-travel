# 标准 RAG 检索 API · 文旅 Agent 接入说明（Spring AI 1.0 / Java 版）

> 本文件为接入参考，示例代码尚未合入工程；实际验证状态见 [验证指南](verification.md#ragflow-接入前验证2026-10-10)。凭据使用 `${RAGFLOW_API_TOKEN}` 占位，真实值从调用环境注入。

版本：v2.0（2026-10-10，基于 Spring AI 1.0 GA 官方文档 + 真实跑通的 run-20261010-105036.log）

框架：文旅 agent 已切换 Spring AI 1.0 GA + Spring Boot 3.x + Java 17+

用途：文旅/cultural-tourism agent 通过本 API 检索文物知识库，获取相关原文片段喂给 LLM 生成回答。

状态：✅ RAGFlow 端到端调通，6 个 query 全部 <1s 返回相关结果，API Token 已生成可用。

---

## 0. 核心结论：RAGFlow API 不受框架切换影响

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

## 1. 接口信息

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

## 2. 请求 / 响应格式

### 请求

```json
{
  "dataset_ids": ["e611c8e0c45111f19cb7ff060c22c24e"],
  "question": "兵马俑是什么时候发现的",
  "page_size": 3,
  "similarity_threshold": 0.2,
  "keyword": false,
  "rerank_id": "bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible"
}
```

| 字段 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| dataset_ids | ✅ | — | 知识库 ID 列表，可传多个 |
| question | ✅ | — | 用户问题，bge-m3 多语言（中英文均可） |
| page_size | ❌ | 30 | 返回 chunk 数，agent 取 3~5 |
| similarity_threshold | ❌ | 0.2 | 相似度下限 |
| keyword | ❌ | — | true=BM25；false=向量；不传=hybrid 混合（推荐） |
| rerank_id | ❌ | — | 重排模型，传了走 rerank 精排 |

### 响应（关键字段）

```json
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
```

| 字段 | 说明 |
| --- | --- |
| data.chunks[].content | 核心：原文片段，拼进 LLM context |
| data.chunks[].similarity | 综合相似度，可据此过滤（<0.3 丢弃） |
| data.chunks[].document_keyword | 来源文档名，做引用溯源 |
| data.chunks[].id | chunk 唯一 ID |
| data.doc_aggs | 每篇文档命中 chunk 数 |
| data.total | 总命中数 |

---

## 3. Maven 依赖（Spring AI 1.0 GA）

```xml
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
```

Spring Boot 3.2+ 与 Spring AI 1.0 GA 配套；JDK 17+。

---

## 4. 两条集成路线（按推荐度）

### 路线 B（推荐）：直接 HTTP 调用 + 自己拼 prompt

文旅 agent 用 Spring RestClient 直接调 RAGFlow /api/v1/retrieval，拿回 chunks，拼进 LLM prompt。最简单、最稳、最快落地，完全绕开 Spring AI RAG 抽象适配成本。

### 路线 A（可选增强）：Spring AI 原生 RAG + 自定义 DocumentRetriever

按官方文档 RetrievalAugmentationAdvisor.builder().documentRetriever(...)，但官方只有 VectorStoreDocumentRetriever（需直连 VectorStore），没有 RAGFlow retriever，需自己实现 DocumentRetriever 接口把 retrieve() 转成调 RAGFlow HTTP API。适合想用 Spring AI 全套 RAG 模块（query 改写、join、post-process）的团队。

---

## 5. 路线 B 代码（推荐，可直接用）

### 5.1 配置（application.yml）

```yaml
travel-agent:
  ragflow:
    base-url: http://172.16.0.154:9380/api/v1
    api-token: ${RAGFLOW_API_TOKEN}
    kb-id: e611c8e0c45111f19cb7ff060c22c24e
    rerank-id: bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible
    page-size: 3
    similarity-threshold: 0.2
    timeout-seconds: 10
```

### 5.2 配置类

```java
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
    private int pageSize = 3;
    private double similarityThreshold = 0.2;
    private int timeoutSeconds = 10;

    // getters / setters 省略，请补全
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getRerankId() { return rerankId; }
    public void setRerankId(String rerankId) { this.rerankId = rerankId; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
```

### 5.3 请求/响应 DTO

```java
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
}
```

```java
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
```

### 5.4 Service（核心检索逻辑）

```java
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
        req.setRerankId(props.getRerankId());

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
```

### 5.5 文旅 Agent 调用 LLM（拼 prompt）

```java
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
```

### 5.6 可选：REST 控制器（供 AgentArts 运行时 /invocations）

```java
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
```

AgentArts 运行时要求镜像提供 POST /invocations 端点（见运行时文档）。上面控制器直接对齐该协议。

---

## 6. 路线 A 代码（可选：Spring AI 原生 RAG）

若团队想用 Spring AI 全套 RAG 模块（query 改写/重排/join），可自定义 DocumentRetriever 适配 RAGFlow。

```java
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
```

```java
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
```

```java
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
```

路线 A 把检索解耦进 advisor，Spring AI 自动把检索到的 Document 拼进 prompt。但需注意：Spring AI 的 DocumentRetriever 接口签名以官方文档为准（上面基于官方文档 1.0 retrieve(Query) 实现）。集成时若编译不匹配，以官方 spring-ai-rag jar 的实际签名为准。

---

## 7. 可达性验证（在 ECS 同 VPC 内跑）

本地（Windows，不在该 VPC）实测 http_code=504（经网关但后端超时），不代表同 VPC 真实情况。请在 ECS 上（与 RAGFlow 同 VPC）跑此脚本验证私网真实可达性。

### 7.1 ECS 上验证脚本（纯 ASCII，用 rlog）

```bash
rlog "curl -s -o /tmp/rag_test -w 'http_code=%{http_code} time=%{time_total}s\n' \
  -X POST http://172.16.0.154:9380/api/v1/retrieval \
  -H 'Authorization: Bearer ${RAGFLOW_API_TOKEN}' \
  -H 'Content-Type: application/json' \
  -d '{\"dataset_ids\":[\"e611c8e0c45111f19cb7ff060c22c24e\"],\"question\":\"terracotta army\",\"page_size\":3,\"rerank_id\":\"bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible\"}'"
rlog "python3 -c \"import json;d=json.load(open('/tmp/rag_test'));print('code=',d['code'],'total=',d['data']['total']);[print('  sim=%.4f'%c['similarity'],c['document_keyword'][:40]) for c in d['data']['chunks'][:3]]\""
```

预期：http_code=200、code= 0、total= 13、打印 3 条 sim>0.6 的 chunk。

### 7.2 Java 侧连通性自检（运行时启动后）

在 agent 运行时内（或本地用公网/VPCE 验证）调用 RagFlowRetrievalService.retrieve("terracotta army")，断言返回非空且 similarity > 0.6 即通。

---

## 8. 网络访问方式（AgentArts 运行时私网对接）

### 推荐：AgentArts 运行时私网访问

AgentArts 运行时部署托管时选私网访问，经 VPC 内网直接调 RAGFlow。

私网访问地址： http://172.16.0.154:9380/api/v1/retrieval

- 端口固定 9380（RAGFlow v0.27.2 Python 后端）。
- ECS 私网 IP 172.16.0.154，与 CSS（172.16.0.53）同在 cn-southwest-2 同一 VPC。

### 8.1 关键前提——ECS 安全组放行 9380

| 配置项 | 值 |
| --- | --- |
| 方向 | 入方向（Inbound） |
| 协议/端口 | TCP / 9380 |
| 源地址 | AgentArts 运行时所在 VPC/子网网段 |
| 描述 | AgentArts 文旅 agent 运行时访问 RAGFlow |

源地址获取：在 AgentArts 控制台部署运行时、选定"私网访问"后，从控制台/运行时详情页确认运行时所在 VPC ID 与子网 CIDR，据此在 ECS 安全组入方向放行该网段访问 TCP 9380。

### 8.2 两步行动清单

1. AgentArts 控制台：部署文旅 agent 运行时 → 选"私网访问" → 记录运行时 VPC/子网网段。
2. 华为云控制台：ECS 安全组 → 入方向规则 → 添加：TCP 9380，源地址 = 运行时子网网段。
3. 运行时内验证：curl http://172.16.0.154:9380/api/v1/retrieval ...（见 §7.1），返回 code:0 即通。

### 8.3 备选：公网访问（不推荐）

```text
http://<ECS公网EIP>:9380/api/v1/retrieval
```

- 需先给 ECS 绑 EIP + 安全组入方向放行 9380。
- ⚠️ RAGFlow 9380 是明文 HTTP，API Token 走公网明文 = 安全风险。仅当私网确不可达时作兜底，并前置加 HTTPS 反代/网关。

当前 ECS 只有 SNAT 出网、无 EIP。私网访问是推荐路径，免 EIP、更安全、时延更低。

---

## 9. 文旅 Agent 集成建议

1. 推荐路线 B（直接 HTTP），1 天内可落地；路线 A（Spring AI 原生 RAG）按需选。
2. 检索参数：page_size=3~5、similarity_threshold=0.2、不传 keyword（hybrid）、传 rerank_id（精排）。
3. 低质过滤：返回 chunk similarity < 0.3 丢弃，避免噪声进 LLM。
4. 引用溯源：回答带 document_keyword（兵马俑/长信宫灯/唐三彩骆驼/青铜面具）。
5. 多语言：bge-m3 多语言，中文问英文库可召回（实测通过）。
6. 超时：RestClient 设 10s（实测 <1s）。
7. 重试：网络抖动重试 2 次。
8. AgentArts 镜像约束：镜像须 ARM64（运行时要求），监听 8080，提供 POST /invocations（见 §5.6 控制器）。

---

## 10. 错误码

| code | 含义 | 处理 |
| --- | --- | --- |
| 0 | 成功 | — |
| 401 | Token 失效/过期 | 检查 API_TOKEN，必要时重新生成 |
| 400 | 参数错误 | 检查 dataset_ids、question 非空 |
| 5xx | 服务异常 | 联系部署同学查容器日志 |

---

## 附录：实测时延（run-20261010-105036.log）

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

## 附录：知识库内容（travel_kb，4 篇文物语料）

| 文档 | 对应文物 |
| --- | --- |
| terracotta-army.md | 秦始皇兵马俑 |
| changxin-palace-lamp.md | 长信宫灯 |
| tang-camel-musicians.md | 唐三彩载乐骆驼俑 |
| bronze-face-mask.md | 商青铜人面具（三星堆） |
