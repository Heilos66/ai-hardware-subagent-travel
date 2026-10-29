package com.travel.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RagFlowIntegrationTest extends AgentTestSupport {
  private AgentSettings ragflow(Map<String, String> overrides) {
    Map<String, String> config = new LinkedHashMap<>();
    config.put("RAGFLOW_URL", base + "/api/v1/retrieval");
    config.put("RAGFLOW_API_TOKEN", "ragflow-secret");
    config.put("RAGFLOW_DATASET_ID", "travel-kb");
    config.put("RAGFLOW_RERANK_ID", "bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible");
    config.putAll(overrides);
    return settings(config);
  }

  private static Map<String, Object> chunk(String content, double score) {
    return Map.of("content", content, "similarity", score, "document_keyword", "terracotta.md");
  }

  private static Reply retrieved(List<?> chunks) {
    return Reply.json(Map.of("code", 0, "data", Map.of("chunks", chunks)));
  }

  private Reply retrievalOrDefault(Captured request) {
    return request.uri().equals("/api/v1/retrieval")
        ? retrieved(List.of(chunk("兵马俑于1974年发现。", .8)))
        : success(request);
  }

  @Test
  void textSearchUsesDocumentContractAndExistingStreamingPrompt() {
    handler = this::retrievalOrDefault;
    var input = input();
    input.put("query", "兵马俑是什么时候发现的");
    var events = run(input, ragflow(Map.of()));
    assertThat(result(events).path("code").asText()).isEqualTo("0");
    assertThat(requests).hasSize(2);
    var retrieval = requests.getFirst();
    assertThat(retrieval.uri()).isEqualTo("/api/v1/retrieval");
    assertThat(retrieval.headers())
        .containsEntry("authorization", "Bearer ragflow-secret")
        .containsEntry("content-type", "application/json")
        .doesNotContainKeys("x-apig-appcode", "x-auth-token");
    assertThat(retrieval.body())
        .isEqualTo(Json.parse("""
            {"dataset_ids":["travel-kb"],"question":"兵马俑是什么时候发现的",
             "page":1,"page_size":3,"similarity_threshold":0.2,
             "rerank_id":"bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible",
             "knn_top_k":64,"knn_num_candidates":128,"rerank_candidates_count":32}
            """));
    var model = requests.getLast();
    assertThat(model.uri()).startsWith("/text/");
    assertThat(model.body().path("stream").asBoolean()).isTrue();
    assertThat(model.body().path("messages").get(0).path("content").asText())
        .contains("不要提及知识库")
        .doesNotContain("中文不超过50字", "English under 50 words", "日本語は50字以内");
    assertThat(model.body().path("messages").get(1).path("content").asText())
        .contains("兵马俑于1974年发现。", "兵马俑是什么时候发现的");
    assertThat(messages(events).stream().map(m -> m.path("text").asText()).toList())
        .containsExactly("", "这是", "秦代陶俑。");
    assertThat(events.toString()).doesNotContain("ragflow-secret", "terracotta.md", "1974");
  }

  @ParameterizedTest
  @CsvSource({"0.7,text,3", "0.69999,text_vision,2"})
  void imageRoutesKeepClassifierThreshold(String confidence, String model, int count) {
    handler = r -> r.uri().equals("/classifier") ? classification(confidence) : retrievalOrDefault(r);
    var input = input();
    input.put("image_url", List.of("https://image.test/exhibit.jpg"));
    assertThat(result(run(input, ragflow(Map.of()))).path("code").asText()).isEqualTo("0");
    assertThat(requests).hasSize(count);
    assertThat(requests.getFirst().headers()).containsEntry("x-api-key", "classifier-secret");
    assertThat(requests.getLast().uri()).startsWith("/" + model + "/");
    if (count == 3) {
      assertThat(requests.get(1).body().path("question").asText()).isEqualTo("检索关于兵马俑的文档");
      assertThat(requests.get(1).body().path("knn_top_k").asInt()).isEqualTo(64);
      assertThat(requests.get(1).body().path("knn_num_candidates").asInt()).isEqualTo(128);
      assertThat(requests.get(1).body().path("rerank_candidates_count").asInt()).isEqualTo(32);
      assertThat(requests.getLast().body().path("messages").get(1).path("content").asText())
          .contains("兵马俑于1974年发现。", "图片识别候选名称：兵马俑");
    } else {
      assertThat(requests).noneMatch(r -> r.uri().contains("retrieval"));
    }
  }

  @Test
  void filteringKeepsBoundaryAndOrderAndCapsEligibleChunks() {
    handler = r -> retrieved(List.of(
        chunk("低分", .2999), chunk(" ", .9), chunk("边界", .3),
        chunk("更高分", .9), chunk("第三条", .8), chunk("不应保留", .7)));
    var config = ragflow(Map.of("RAGFLOW_RERANK_ID", ""));
    var chunks = new ExternalServices(http, config).search("问题").block();
    assertThat(chunks).extracting(ExternalServices.KnowledgeChunk::content)
        .containsExactly("边界", "更高分", "第三条");
    assertThat(requests.getFirst().body().has("rerank_id")).isFalse();
    assertThat(requests.getFirst().body().has("keyword")).isFalse();
  }

  @Test
  void disabledRetrievalWithoutImageRoutesToTextModel(@TempDir Path directory) {
    handler = r -> retrievalOrDefault(r);
    var input = input();
    var config = ragflow(Map.of("RAGFLOW_ENABLED", "false", "RAGFLOW_API_TOKEN", "",
        "RAGFLOW_API_TOKEN_FILE", directory.resolve("missing-token").toString()));
    var events = run(input, config);
    assertThat(result(events).path("code").asText()).isEqualTo("0");
    assertThat(requests).hasSize(1).noneMatch(r -> r.uri().contains("retrieval"));
    var request = requests.getLast();
    assertThat(request.uri()).startsWith("/text/");
    assertThat(request.body().path("stream").asBoolean()).isTrue();
    assertThat(request.body().path("messages").get(1).path("content").asText())
        .contains("当前没有可用的内部资料", "介绍这个展品")
        .doesNotContain("1974", "资料 1：");
    assertThat(messages(events).stream().map(m -> m.path("text").asText()).toList())
        .containsExactly("", "这是", "秦代陶俑。");
  }

  @Test
  void disabledRetrievalWithImageRoutesToTextModel(@TempDir Path directory) {
    handler = r -> r.uri().equals("/classifier") ? classification("0.7") : retrievalOrDefault(r);
    var input = input();
    input.put("image_url", List.of("https://image.test/exhibit.jpg"));
    var config = ragflow(Map.of("RAGFLOW_ENABLED", "false", "RAGFLOW_API_TOKEN", "",
        "RAGFLOW_API_TOKEN_FILE", directory.resolve("missing-token").toString()));
    var events = run(input, config);
    assertThat(result(events).path("code").asText()).isEqualTo("0");
    assertThat(requests).hasSize(2).noneMatch(r -> r.uri().contains("retrieval"));
    assertThat(requests.getFirst().uri()).isEqualTo("/classifier");
    var request = requests.getLast();
    assertThat(request.uri()).startsWith("/text/");
    assertThat(request.body().path("stream").asBoolean()).isTrue();
    assertThat(request.body().path("messages").get(1).path("content").asText())
        .contains("当前没有可用的内部资料", "介绍这个展品", "图片识别候选名称：兵马俑")
        .doesNotContain("1974", "资料 1：");
    assertThat(messages(events).stream().map(m -> m.path("text").asText()).toList())
        .containsExactly("", "这是", "秦代陶俑。");
  }

  @Test
  void disabledRetrievalWithImageRoutesToVisionModel(@TempDir Path directory) {
    handler = r -> r.uri().equals("/classifier") ? classification("0.69999") : retrievalOrDefault(r);
    var input = input();
    input.put("image_url", List.of("https://image.test/exhibit.jpg"));
    var config = ragflow(Map.of("RAGFLOW_ENABLED", "false", "RAGFLOW_API_TOKEN", "",
        "RAGFLOW_API_TOKEN_FILE", directory.resolve("missing-token").toString()));
    var events = run(input, config);
    assertThat(result(events).path("code").asText()).isEqualTo("0");
    assertThat(requests).hasSize(2).noneMatch(r -> r.uri().contains("retrieval"));
    assertThat(requests.getFirst().uri()).isEqualTo("/classifier");
    var request = requests.getLast();
    assertThat(request.uri()).startsWith("/text_vision/");
    assertThat(request.body().path("stream").asBoolean()).isTrue();
    assertThat(request.body().path("messages").get(1).path("content").get(1)
        .path("image_url").path("url").asText()).isEqualTo("https://image.test/exhibit.jpg");
    assertThat(messages(events).stream().map(m -> m.path("text").asText()).toList())
        .containsExactly("", "这是", "秦代陶俑。");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void genuineEmptyOrFilteredResultsContinueWithEmptyContext(boolean filtered) {
    handler = r -> r.uri().contains("retrieval")
        ? retrieved(filtered ? List.of(chunk("低分资料", .1)) : List.of()) : success(r);
    assertThat(result(run(input(), ragflow(Map.of()))).path("code").asText()).isEqualTo("0");
    assertThat(requests.getLast().body().path("messages").get(1).path("content").asText())
        .contains("当前没有可用的内部资料").doesNotContain("低分资料");
  }

  @Test
  void contextLimitStillApplies() {
    handler = r -> r.uri().contains("retrieval")
        ? retrieved(List.of(chunk("知".repeat(1000) + "不可见尾部", .8))) : success(r);
    assertThat(result(run(input(), ragflow(Map.of("MAX_CONTEXT_CHARS", "80"))))
        .path("code").asText()).isEqualTo("0");
    String prompt = requests.getLast().body().path("messages").get(1).path("content").asText();
    assertThat(prompt).contains("知").doesNotContain("不可见尾部");
    assertThat(Json.length(prompt.split("内部资料：", 2)[1].split("\n\n用户问题：", 2)[0]))
        .isEqualTo(80);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 302})
  void permanentHttpFailuresAreNotRetriedOrHidden(int status) {
    handler = r -> new Reply(status, "application/json", Flux.just("private-upstream-secret"));
    var events = run(input(), ragflow(Map.of()));
    assertThat(result(events).path("code").asText()).isEqualTo("E5002");
    assertThat(result(events).path("message").asText()).contains("HTTP " + status);
    assertThat(requests).hasSize(1);
    assertThat(events.toString()).doesNotContain("private-upstream-secret", "ragflow-secret");
  }

  @ParameterizedTest
  @ValueSource(ints = {408, 429, 503})
  void temporaryHttpFailureRetriesBeforeSuccess(int status) {
    AtomicInteger attempts = new AtomicInteger();
    handler = r -> r.uri().contains("retrieval") && attempts.incrementAndGet() <= 2
        ? new Reply(status, "application/json", Flux.just("private-upstream-secret"))
        : retrievalOrDefault(r);
    assertThat(result(run(input(), ragflow(Map.of()))).path("code").asText()).isEqualTo("0");
    assertThat(attempts.get()).isEqualTo(3);
    assertThat(requests).hasSize(4);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2})
  void retriesAreBoundedAndExhaustionStopsBeforeModel(int retries) {
    handler = r -> new Reply(503, "application/json", Flux.just("private-upstream-secret"));
    var events = run(input(), ragflow(Map.of("RAGFLOW_RETRIES", String.valueOf(retries))));
    assertThat(result(events).path("code").asText()).isEqualTo("E5002");
    assertThat(result(events).path("message").asText()).contains("HTTP 503");
    assertThat(requests).hasSize(retries + 1).allMatch(r -> r.uri().contains("retrieval"));
    assertThat(events.toString()).doesNotContain("private-upstream-secret");
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "{\"code\":102,\"message\":\"private-upstream-secret\",\"data\":{\"chunks\":[]}}",
    "{\"code\":\"0\",\"data\":{\"chunks\":[]}}",
    "{\"code\":0,\"data\":{}}",
    "{\"code\":0,\"data\":{\"chunks\":[{\"content\":\"x\"}]}}",
    "{\"code\":0,\"data\":{\"chunks\":[{\"similarity\":0.5}]}}",
    "{\"code\":0,\"data\":{\"chunks\":[{\"content\":\"x\",\"similarity\":\"0.5\"}]}}",
    "private-upstream-secret", ""
  })
  void malformedAndBusinessErrorsDoNotBecomeEmptyKnowledge(String body) {
    handler = r -> new Reply(200, "application/json", Flux.just(body));
    var events = run(input(), ragflow(Map.of()));
    assertThat(result(events).path("code").asText()).isEqualTo("E5002");
    assertThat(requests).hasSize(1);
    assertThat(events.toString()).doesNotContain("private-upstream-secret");
  }

  @Test
  void deadlineCoversRetriesAndCancelsPendingRequest() {
    AtomicInteger attempts = new AtomicInteger();
    AtomicBoolean cancelled = new AtomicBoolean();
    WebClient client = WebClient.builder().exchangeFunction(request ->
        attempts.incrementAndGet() == 1
            ? Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build())
            : Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true))).build();
    StepVerifier.withVirtualTime(() -> new ExternalServices(client,
            ragflow(Map.of("RAGFLOW_TIMEOUT", "1"))).search("问题"))
        .expectSubscription()
        .thenAwait(Duration.ofMillis(999))
        .then(() -> assertThat(attempts.get()).isEqualTo(2))
        .thenAwait(Duration.ofMillis(1))
        .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(AgentException.class)
            .hasMessage("RAGFlow知识检索调用超时"))
        .verify(Duration.ofSeconds(2));
    assertThat(cancelled).isTrue();
  }

  @Test
  void downstreamCancellationStopsRetrievalWithoutRetry() {
    AtomicInteger attempts = new AtomicInteger();
    AtomicBoolean cancelled = new AtomicBoolean();
    WebClient client = WebClient.builder().exchangeFunction(request -> {
      attempts.incrementAndGet();
      return Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true));
    }).build();
    StepVerifier.create(new ExternalServices(client, ragflow(Map.of())).search("问题"))
        .expectSubscription().thenAwait(Duration.ofMillis(10))
        .thenCancel().verify(Duration.ofSeconds(2));
    assertThat(attempts.get()).isEqualTo(1);
    assertThat(cancelled).isTrue();
  }

  @Test
  void mountedTokenIsReReadOnEachSearch(@TempDir Path directory) throws Exception {
    Path token = directory.resolve("token");
    handler = this::retrievalOrDefault;
    var config = ragflow(Map.of("RAGFLOW_API_TOKEN_FILE", token.toString()));
    var services = new ExternalServices(http, config);
    Files.writeString(token, "first-token\n");
    services.search("问题").block(Duration.ofSeconds(2));
    Files.writeString(token, "rotated-token\n");
    services.search("问题").block(Duration.ofSeconds(2));
    assertThat(requests.getFirst().headers()).containsEntry("authorization", "Bearer first-token");
    assertThat(requests.getLast().headers()).containsEntry("authorization", "Bearer rotated-token");
    assertThat(config.ragflow.toString()).doesNotContain("ragflow-secret", token.toString());
    Files.delete(token);
    assertThatThrownBy(() -> services.search("问题").block(Duration.ofSeconds(2)))
        .isInstanceOf(AgentException.class).hasMessage("RAGFlow Token文件不可读取");
    assertThat(requests).hasSize(2);
  }

  @ParameterizedTest
  @CsvSource(value = {
    "RAGFLOW_API_TOKEN|", "RAGFLOW_API_TOKEN|E1-encrypted", "RAGFLOW_API_TOKEN|invalid token",
    "RAGFLOW_URL|", "RAGFLOW_DATASET_ID|", "RAGFLOW_DATASET_ID|invalid/id"
  }, delimiter = '|', nullValues = "")
  void missingOrInvalidConfigurationStopsBeforeNetwork(String name, String value) {
    var events = run(input(), ragflow(Map.of(name, value == null ? "" : value)));
    assertThat(result(events).path("code").asText()).isEqualTo("E5004");
    assertThat(requests).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "RAGFLOW_RETRIES,0.5", "RAGFLOW_RETRIES,3", "RAGFLOW_TIMEOUT,0",
    "RAGFLOW_TOP_K,0", "RAGFLOW_MIN_SIMILARITY,NaN", "RAGFLOW_SIMILARITY_THRESHOLD,1.1"
  })
  void invalidLimitsFailAtStartup(String name, String value) {
    assertThatThrownBy(() -> ragflow(Map.of(name, value))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void customCandidateCountsAreIndependentOfReturnedTopK() {
    handler = this::retrievalOrDefault;
    assertThat(result(run(input(), ragflow(Map.of(
        "RAGFLOW_TOP_K", "2", "RAGFLOW_KNN_TOP_K", "96",
        "RAGFLOW_KNN_NUM_CANDIDATES", "192", "RAGFLOW_RERANK_CANDIDATES_COUNT", "48"))))
        .path("code").asText()).isEqualTo("0");
    var body = requests.getFirst().body();
    assertThat(body.path("page_size").asInt()).isEqualTo(2);
    assertThat(body.path("knn_top_k").isIntegralNumber()).isTrue();
    assertThat(body.path("knn_top_k").asInt()).isEqualTo(96);
    assertThat(body.path("knn_num_candidates").asInt()).isEqualTo(192);
    assertThat(body.path("rerank_candidates_count").asInt()).isEqualTo(48);
    assertThat(body.has("use_rerank")).isFalse();
  }

  @ParameterizedTest
  @CsvSource({"true,reranker,true", "false,reranker,false", "true,,false", "false,,false"})
  void rerankRequiresBothTheSwitchAndAnId(boolean enabled, String id, boolean sent) {
    handler = this::retrievalOrDefault;
    assertThat(result(run(input(), ragflow(Map.of(
        "RAGFLOW_USE_RERANK", Boolean.toString(enabled), "RAGFLOW_RERANK_ID", id == null ? "" : id))))
        .path("code").asText()).isEqualTo("0");
    var body = requests.getFirst().body();
    assertThat(body.has("rerank_id")).isEqualTo(sent);
    assertThat(body.has("rerank_candidates_count")).isEqualTo(sent);
    assertThat(body.path("knn_top_k").asInt()).isEqualTo(64);
    assertThat(body.path("knn_num_candidates").asInt()).isEqualTo(128);
  }

  @ParameterizedTest
  @CsvSource(value = {
    "RAGFLOW_KNN_TOP_K|knn_top_k|", "RAGFLOW_KNN_TOP_K|knn_top_k|null",
    "RAGFLOW_KNN_NUM_CANDIDATES|knn_num_candidates|", "RAGFLOW_KNN_NUM_CANDIDATES|knn_num_candidates|NULL",
    "RAGFLOW_RERANK_CANDIDATES_COUNT|rerank_candidates_count|",
    "RAGFLOW_RERANK_CANDIDATES_COUNT|rerank_candidates_count| null "
  }, delimiter = '|')
  void explicitlyEmptyCandidateCountsAreOmittedIndividually(String variable, String field, String value) {
    handler = this::retrievalOrDefault;
    assertThat(result(run(input(), ragflow(Map.of(variable, value == null ? "" : value))))
        .path("code").asText()).isEqualTo("0");
    var body = requests.getFirst().body();
    assertThat(body.has(field)).isFalse();
    assertThat(body.has("rerank_id")).isTrue();
    for (String other : List.of("knn_top_k", "knn_num_candidates", "rerank_candidates_count")) {
      if (!other.equals(field)) {
        assertThat(body.path(other).isIntegralNumber()).isTrue();
      }
    }
  }

  @Test
  void allCandidateCountsCanFallBackToTheServerDefaults() {
    handler = this::retrievalOrDefault;
    assertThat(result(run(input(), ragflow(Map.of(
        "RAGFLOW_KNN_TOP_K", "null", "RAGFLOW_KNN_NUM_CANDIDATES", "null",
        "RAGFLOW_RERANK_CANDIDATES_COUNT", "null"))))
        .path("code").asText()).isEqualTo("0");
    assertThat(requests.getFirst().body()).isEqualTo(Json.parse("""
        {"dataset_ids":["travel-kb"],"question":"介绍这个展品","page":1,"page_size":3,
         "similarity_threshold":0.2,"rerank_id":"bge-reranker-v2-m3@maas-hw@OpenAI-API-Compatible"}
        """));
  }

  @ParameterizedTest
  @CsvSource({
    "RAGFLOW_KNN_TOP_K,0", "RAGFLOW_KNN_TOP_K,-1", "RAGFLOW_KNN_TOP_K,1.5",
    "RAGFLOW_KNN_TOP_K,NaN", "RAGFLOW_KNN_TOP_K,2147483648", "RAGFLOW_KNN_TOP_K,private-invalid-value",
    "RAGFLOW_KNN_NUM_CANDIDATES,0", "RAGFLOW_KNN_NUM_CANDIDATES,-1", "RAGFLOW_KNN_NUM_CANDIDATES,1.5",
    "RAGFLOW_KNN_NUM_CANDIDATES,NaN", "RAGFLOW_KNN_NUM_CANDIDATES,2147483648", "RAGFLOW_KNN_NUM_CANDIDATES,private-invalid-value",
    "RAGFLOW_RERANK_CANDIDATES_COUNT,0", "RAGFLOW_RERANK_CANDIDATES_COUNT,-1", "RAGFLOW_RERANK_CANDIDATES_COUNT,1.5",
    "RAGFLOW_RERANK_CANDIDATES_COUNT,NaN", "RAGFLOW_RERANK_CANDIDATES_COUNT,2147483648", "RAGFLOW_RERANK_CANDIDATES_COUNT,private-invalid-value"
  })
  void invalidCandidateCountsFailBeforeRequestsWithoutEchoingValues(String variable, String value) {
    assertThatThrownBy(() -> ragflow(Map.of(variable, value)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(variable)
        .hasMessageNotContaining(value);
    assertThat(requests).isEmpty();
  }

  @Test
  void invalidRerankSwitchFailsAtStartup() {
    assertThatThrownBy(() -> ragflow(Map.of("RAGFLOW_USE_RERANK", "private-invalid-value")))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RAGFLOW_USE_RERANK")
        .hasMessageNotContaining("private-invalid-value");
  }

}
