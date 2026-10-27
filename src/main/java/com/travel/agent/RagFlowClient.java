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
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/** RAGFlow retrieval only; preserves cancellation and never exposes upstream error bodies. */
final class RagFlowClient {
  private final WebClient http;
  private final AgentSettings.RagFlow settings;
  private final WorkflowDiagnostics diagnostics;

  RagFlowClient(WebClient http, AgentSettings settings) {
    this.http = http;
    this.settings = settings.ragflow;
    this.diagnostics = new WorkflowDiagnostics(settings);
  }

  Mono<List<ExternalServices.KnowledgeChunk>> search(String query) {
    return Mono.deferContextual(context -> {
      if (!settings.enabled()) {
        diagnostics.retrievalSkipped(context);
        return Mono.just(List.of());
      }
      AtomicInteger attempts = new AtomicInteger();
      return Mono.defer(
              () -> {
                if (settings.url().isEmpty())
                  return Mono.error(new AgentException("E5004", "RAGFlow地址未配置：RAGFLOW_URL"));
                if (settings.datasetId().isEmpty())
                  return Mono.error(new AgentException("E5004", "RAGFlow知识库ID未配置：RAGFLOW_DATASET_ID"));
                if (!AgentSettings.validId(settings.datasetId()))
                  return Mono.error(new AgentException("E5004", "RAGFlow知识库ID格式错误：RAGFLOW_DATASET_ID"));
                if (query.isBlank() || Json.length(query) > 1000)
                  return Mono.error(new AgentException("400", "检索内容必须为1至1000个字符"));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("dataset_ids", List.of(settings.datasetId()));
                body.put("question", query);
                body.put("page", 1);
                body.put("page_size", settings.topK());
                body.put("similarity_threshold", settings.threshold());
                if (settings.knnTopK() != null) body.put("knn_top_k", settings.knnTopK());
                if (settings.knnNumCandidates() != null)
                  body.put("knn_num_candidates", settings.knnNumCandidates());
                if (settings.rerankEnabled()) {
                  body.put("rerank_id", settings.rerankId());
                  if (settings.rerankCandidatesCount() != null)
                    body.put("rerank_candidates_count", settings.rerankCandidatesCount());
                }
                // Leave keyword/vector weights at server defaults; keyword=false is not vector-only.
                return token().flatMap(token ->
                    Mono.defer(() -> {
                      attempts.incrementAndGet();
                      return request(body, token);
                    })
                        .retryWhen(
                            Retry.backoff(settings.retries(), Duration.ofMillis(100))
                                .maxBackoff(Duration.ofMillis(500))
                                .jitter(0)
                                .filter(error -> error instanceof RetryableStatus
                                    || error instanceof WebClientRequestException)
                                .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                        .map(response -> {
                          var chunks = chunks(response);
                          diagnostics.retrievalResult(context, query, response.path("data").path("chunks"),
                              chunks, attempts.get(), token);
                          return chunks;
                        }));
              })
          // One deadline covers credential loading, all attempts, and retry delays.
          .timeout(settings.timeout())
          .onErrorMap(error -> {
            if (error instanceof AgentException) return error;
            if (error instanceof RetryableStatus status) return httpError(status.status);
            return new AgentException("E5002", error instanceof TimeoutException
                ? "RAGFlow知识检索调用超时" : "RAGFlow知识检索连接失败或响应格式错误");
          })
          .doOnError(error -> diagnostics.retrievalFailure(context, attempts.get(), error));
    });
  }

  private Mono<String> token() {
    Mono<String> source =
        settings.tokenFile().isEmpty()
            ? Mono.just(settings.token())
            : Mono.fromCallable(() -> Files.readString(Path.of(settings.tokenFile())))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(error -> new AgentException("E5004", "RAGFlow Token文件不可读取"));
    return source.map(
        value -> {
          value = value.strip();
          if (value.isEmpty()
              || value.startsWith("E1-")
              || value.startsWith("${")
              || value.chars().anyMatch(Character::isWhitespace)
              || value.chars().anyMatch(Character::isISOControl))
            throw new AgentException("E5004", "RAGFlow Token未配置或格式错误");
          return value;
        });
  }

  private Mono<JsonNode> request(Map<String, Object> body, String token) {
    return http.post()
        .uri(AgentSettings.httpUri(settings.url()))
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .headers(headers -> headers.setBearerAuth(token))
        .bodyValue(body)
        .exchangeToMono(
            response -> {
              int status = response.statusCode().value();
              if (status == 408 || status == 429 || status >= 500)
                return Mono.error(new RetryableStatus(status));
              if (!response.statusCode().is2xxSuccessful()) return Mono.error(httpError(status));
              return response
                  .bodyToMono(JsonNode.class)
                  .switchIfEmpty(Mono.error(new AgentException("E5002", "RAGFlow检索响应为空")));
            });
  }

  private List<ExternalServices.KnowledgeChunk> chunks(JsonNode body) {
    JsonNode code = body.path("code");
    if (!code.isIntegralNumber() || !code.canConvertToInt() || code.intValue() != 0)
      throw new AgentException("E5002", "RAGFlow知识检索返回业务错误");
    JsonNode chunks = body.path("data").path("chunks");
    if (!chunks.isArray()) throw new AgentException("E5002", "RAGFlow检索响应缺少chunks");
    List<ExternalServices.KnowledgeChunk> result = new ArrayList<>();
    for (JsonNode chunk : chunks) {
      JsonNode score = chunk.path("similarity");
      if (!chunk.isObject()
          || !chunk.path("content").isTextual()
          || !score.isNumber()
          || !Double.isFinite(score.doubleValue()))
        throw new AgentException("E5002", "RAGFlow检索分片格式错误");
      String content = chunk.path("content").asText().strip();
      if (content.isEmpty() || score.doubleValue() < settings.minSimilarity()) continue;
      result.add(
          new ExternalServices.KnowledgeChunk(
              content, chunk.path("document_keyword").asText(""),
              chunk.path("id").asText(""), score.doubleValue()));
      if (result.size() == settings.topK()) break;
    }
    return List.copyOf(result);
  }

  private static AgentException httpError(int status) {
    return new AgentException("E5002", "RAGFlow知识检索返回HTTP " + status);
  }

  private static final class RetryableStatus extends RuntimeException {
    final int status;

    RetryableStatus(int status) {
      this.status = status;
    }
  }
}
