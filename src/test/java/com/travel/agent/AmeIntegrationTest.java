package com.travel.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

class AmeIntegrationTest extends AgentTestSupport {
  private AgentSettings ame(Map<String, String> overrides) {
    var values = new java.util.HashMap<>(overrides);
    values.put("KNOWLEDGE_BACKEND", "ame");
    return settings(values);
  }

  @Test
  void defaultsKeepRagflowAndRejectUnknownBackend() {
    assertThat(settings().knowledgeBackend).isEqualTo(AgentSettings.KnowledgeBackend.RAGFLOW);
    assertThat(settings().ragflow.retries()).isEqualTo(2);
    assertThatThrownBy(() -> settings(Map.of("KNOWLEDGE_BACKEND", "unknown")))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("KNOWLEDGE_BACKEND");
  }

  @Test
  void requestUsesCompatibleProtocolWithoutRagflowSpecificOptions() {
    var settings = ame(Map.of("RAGFLOW_RERANK_ID", "old-model", "RAGFLOW_TOP_K", "5",
        "RAGFLOW_USE_RERANK", "true", "RAGFLOW_KNN_TOP_K", "64"));
    new RagFlowClient(http, settings).search("长信宫灯").block(Duration.ofSeconds(5));
    var request = requests.getFirst();
    assertThat(request.headers().get("authorization")).isEqualTo("Bearer search-secret");
    assertThat(request.body()).isEqualTo(Json.MAPPER.valueToTree(Map.of(
        "dataset_ids", List.of("travel-kb"), "question", "长信宫灯", "page", 1,
        "page_size", 5, "similarity_threshold", 0.0)));
    assertThat(settings.ragflow.retries()).isZero();
  }

  @ParameterizedTest
  @ValueSource(doubles = {-2.0, 0.0, 0.1, 17.5})
  void graphScoresAreNotFilteredAsCosineSimilarities(double score) {
    handler = request -> Reply.json(Map.of("code", 0, "data", Map.of("chunks", List.of(
        Map.of("content", "长信宫灯", "similarity", score)))));
    var chunks = new RagFlowClient(http, ame(Map.of())).search("灯").block(Duration.ofSeconds(5));
    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().similarity()).isEqualTo(score);
  }

  @Test
  void disabledRetrievalDoesNotLoadCredentialsOrMakeHttpRequests() {
    var settings = ame(Map.of("RAGFLOW_ENABLED", "false", "RAGFLOW_URL", "",
        "RAGFLOW_API_TOKEN_FILE", "/does-not-exist"));
    assertThat(new RagFlowClient(http, settings).search("介绍展品").block()).isEmpty();
    assertThat(requests).isEmpty();
    assertThat(messages(run(input(), settings))).isNotEmpty();
    assertThat(requests).allMatch(request -> request.uri().startsWith("/text/"));
  }

  @Test
  void acceptsTenThousandUnicodeCodePointsAndRejectsOverflow() {
    var client = new RagFlowClient(http, ame(Map.of()));
    client.search("灯".repeat(10000)).block(Duration.ofSeconds(5));
    assertThat(requests.getFirst().body().path("question").asText()).hasSize(10000);
    assertThatThrownBy(() -> client.search("灯".repeat(10001)).block())
        .isInstanceOf(AgentException.class).hasMessageContaining("10000");
    assertThat(requests).hasSize(1);
  }

  @Test
  void transientFailureDoesNotRetryByDefaultOrExposeUpstreamBody() {
    handler = request -> new Reply(503, "application/json", Flux.just("secret-upstream-error"));
    assertThatThrownBy(() -> new RagFlowClient(http, ame(Map.of())).search("灯").block())
        .isInstanceOf(AgentException.class).hasMessage("AME知识检索返回HTTP 503");
    assertThat(requests).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"70%", "69%"})
  void imageRoutingAndModelStreamingStayUnchanged(String confidence) {
    handler = request -> request.uri().startsWith("/classifier")
        ? classification(confidence) : success(request);
    var raw = input();
    raw.put("image_url", List.of("https://example.com/image.jpg"));
    assertThat(messages(run(raw, ame(Map.of())))).isNotEmpty();
    boolean high = confidence.equals("70%");
    assertThat(requests.stream().anyMatch(r -> r.uri().startsWith("/api/v1/retrieval")))
        .isEqualTo(high);
    var model = requests.getLast();
    assertThat(model.uri()).startsWith(high ? "/text/" : "/text_vision/");
    assertThat(model.body().path("stream").asBoolean()).isTrue();
    if (high) {
      assertThat(model.body().toString()).contains("秦代陶俑");
    }
  }
}
