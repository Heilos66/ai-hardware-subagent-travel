package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;
import reactor.util.retry.Retry;

/** RAGFlow-compatible remote retrieval; preserves cancellation and never exposes upstream error bodies. */
final class RagFlowClient {
  private final WebClient http;
  private final AgentSettings.KnowledgeBackend backend;
  private final AgentSettings.RagFlow settings;
  private final int maxQueryChars;
  private final WorkflowDiagnostics diagnostics;

  RagFlowClient(WebClient http, AgentSettings settings) {
    this.http = http;
    this.backend = settings.knowledgeBackend;
    this.settings = settings.ragflow;
    this.maxQueryChars = settings.maxQueryChars;
    this.diagnostics = new WorkflowDiagnostics(settings);
  }

  Mono<List<ExternalServices.KnowledgeChunk>> search(String query) {
    return Mono.deferContextual(context -> search(query, context));
  }

  private Mono<List<ExternalServices.KnowledgeChunk>> search(String query, ContextView context) {
    if (!settings.enabled()) {
      diagnostics.retrievalSkipped(context);
      return Mono.just(List.of());
    }
    AtomicInteger attempts = new AtomicInteger();
    return Mono.defer(() -> retrieve(query, context, attempts))
        // One deadline covers credential loading, all attempts, and retry delays.
        .timeout(settings.timeout())
        .onErrorMap(this::mapSearchError)
        .doOnError(error -> diagnostics.retrievalFailure(context, attempts.get(), error));
  }

  private Mono<List<ExternalServices.KnowledgeChunk>> retrieve(
      String query, ContextView context, AtomicInteger attempts) {
    validateSearchConfig(query);
    Map<String, Object> body = buildSearchBody(query);
    // Read credentials once per search subscription, outside HTTP retries.
    return token().flatMap(token -> requestWithRetry(body, token, attempts)
        .map(response -> readSearchResult(response, query, context, attempts.get(), token)));
  }

  private Mono<JsonNode> requestWithRetry(
      Map<String, Object> body, String token, AtomicInteger attempts) {
    return Mono.defer(() -> {
      attempts.incrementAndGet();
      return request(body, token);
    }).retryWhen(
        Retry.backoff(settings.retries(), Duration.ofMillis(100))
            .maxBackoff(Duration.ofMillis(500))
            .jitter(0.2)
            .filter(error -> error instanceof RetryableStatus
                || error instanceof WebClientRequestException)
            .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
  }

  private List<ExternalServices.KnowledgeChunk> readSearchResult(
      JsonNode response, String query, ContextView context, int attempts, String token) {
    var chunks = chunks(response);
    diagnostics.retrievalResult(context, query, response, chunks, attempts, token);
    return chunks;
  }

  private void validateSearchConfig(String query) {
    if (settings.url().isEmpty()) {
      throw new AgentException("E5004", backend.label + "地址未配置：RAGFLOW_URL");
    }
    if (settings.datasetId().isEmpty()) {
      throw new AgentException("E5004", backend.label + "知识库ID未配置：RAGFLOW_DATASET_ID");
    }
    if (!AgentSettings.validId(settings.datasetId())) {
      throw new AgentException("E5004", backend.label + "知识库ID格式错误：RAGFLOW_DATASET_ID");
    }
    if (query.isBlank() || Json.length(query) > maxQueryChars) {
      throw new AgentException("400", "检索内容必须为1至" + maxQueryChars + "个字符");
    }
  }

  private Map<String, Object> buildSearchBody(String query) {
    // Leave keyword/vector weights at server defaults; keyword=false is not vector-only.
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("dataset_ids", List.of(settings.datasetId()));
    body.put("question", query);
    body.put("page", 1);
    body.put("page_size", settings.topK());
    body.put("similarity_threshold", settings.threshold());
    if (settings.knnTopK() != null) {
      body.put("knn_top_k", settings.knnTopK());
    }
    if (settings.knnNumCandidates() != null) {
      body.put("knn_num_candidates", settings.knnNumCandidates());
    }
    if (settings.isRerankEnabled()) {
      body.put("rerank_id", settings.rerankId());
      if (settings.rerankCandidatesCount() != null) {
        body.put("rerank_candidates_count", settings.rerankCandidatesCount());
      }
    }
    return body;
  }

  private Throwable mapSearchError(Throwable error) {
    if (error instanceof AgentException) {
      return error;
    }
    if (error instanceof RetryableStatus status) {
      return httpError(status.status);
    }
    return new AgentException("E5002", error instanceof TimeoutException
        ? backend.label + "知识检索调用超时" : backend.label + "知识检索连接失败或响应格式错误", error);
  }

  private Mono<String> token() {
    Mono<String> source =
        settings.tokenFile().isEmpty()
            ? Mono.just(settings.token())
            : Mono.fromCallable(() -> Files.readString(Path.of(settings.tokenFile())))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(error -> new AgentException("E5004", backend.label + " Token文件不可读取", error));
    return source.map(this::validateToken);
  }

  private String validateToken(String value) {
    String token = value.strip();
    if (token.isEmpty()
        || token.startsWith("E1-")
        || token.startsWith("${")
        || token.chars().anyMatch(Character::isWhitespace)
        || token.chars().anyMatch(Character::isISOControl)) {
      throw new AgentException("E5004", backend.label + " Token未配置或格式错误");
    }
    return token;
  }

  private Mono<JsonNode> request(Map<String, Object> body, String token) {
    return http.post()
        .uri(AgentSettings.httpUri(settings.url()))
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .headers(headers -> headers.setBearerAuth(token))
        .bodyValue(body)
        .exchangeToMono(this::readResponse);
  }

  private Mono<JsonNode> readResponse(ClientResponse response) {
    int status = response.statusCode().value();
    if (status == 408 || status == 429 || status >= 500) {
      return Mono.error(new RetryableStatus(status));
    }
    if (!response.statusCode().is2xxSuccessful()) {
      return Mono.error(httpError(status));
    }
    return response.bodyToMono(JsonNode.class)
        .switchIfEmpty(Mono.error(new AgentException("E5002", backend.label + "检索响应为空")));
  }

  private List<ExternalServices.KnowledgeChunk> chunks(JsonNode body) {
    JsonNode chunks = validateResponseBody(body);
    List<ExternalServices.KnowledgeChunk> result = new ArrayList<>();
    for (JsonNode chunk : chunks) {
      if (!validChunk(chunk)) {
        throw new AgentException("E5002", backend.label + "检索分片格式错误");
      }
      String content = chunk.path("content").asText().strip();
      double score = chunk.path("similarity").doubleValue();
      if (content.isEmpty()
          || (backend == AgentSettings.KnowledgeBackend.RAGFLOW && score < settings.minSimilarity())) {
        continue;
      }
      result.add(toKnowledgeChunk(chunk, content, score));
      if (result.size() == settings.topK()) {
        break;
      }
    }
    return List.copyOf(result);
  }

  private JsonNode validateResponseBody(JsonNode body) {
    JsonNode code = body.path("code");
    if (!code.isIntegralNumber() || !code.canConvertToInt() || code.intValue() != 0) {
      throw new AgentException("E5002", backend.label + "知识检索返回业务错误");
    }
    JsonNode chunks = body.path("data").path("chunks");
    if (!chunks.isArray()) {
      throw new AgentException("E5002", backend.label + "检索响应缺少chunks");
    }
    return chunks;
  }

  private static boolean validChunk(JsonNode chunk) {
    JsonNode score = chunk.path("similarity");
    return chunk.isObject()
        && chunk.path("content").isTextual()
        && score.isNumber()
        && Double.isFinite(score.doubleValue());
  }

  private static ExternalServices.KnowledgeChunk toKnowledgeChunk(
      JsonNode chunk, String content, double score) {
    return new ExternalServices.KnowledgeChunk(
        content, chunk.path("document_keyword").asText(""),
        chunk.path("id").asText(""), score);
  }

  private AgentException httpError(int status) {
    return new AgentException("E5002", backend.label + "知识检索返回HTTP " + status);
  }

  private static final class RetryableStatus extends RuntimeException {
    final int status;

    RetryableStatus(int status) {
      this.status = status;
    }
  }
}
