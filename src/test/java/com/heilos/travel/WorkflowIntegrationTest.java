package com.heilos.travel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

class WorkflowIntegrationTest extends AgentTestSupport {
  @Test
  void textRouteAndIndependentReasoningDeltas() {
    handler =
        r ->
            r.uri().startsWith("/api/v1/retrieval")
                ? success(r)
                : Reply.sse(
                    delta("", "先识别。")
                        + delta("", "再检索。")
                        + delta(" 讲解", "")
                        + delta("正文\n", "")
                        + DONE);
    List<JsonNode> events = run(input());
    assertThat(result(events)).isEqualTo(Json.parse("{\"code\":\"0\",\"message\":\" 讲解正文\\n\"}"));
    assertThat(messages(events).stream().map(m -> m.path("reasoning_content").asText()).toList())
        .containsExactly("先识别。", "再检索。", "", "");
    assertThat(messages(events).stream().map(m -> m.path("text").asText()).toList())
        .containsExactly("", "", " 讲解", "正文\n");
    assertThat(requests).hasSize(2);
    assertThat(requests.getFirst().headers())
        .containsEntry("authorization", "Bearer search-secret");
    assertThat(requests.getFirst().body())
        .isEqualTo(
            Json.parse(
                "{\"dataset_ids\":[\"travel-kb\"],\"question\":\"介绍这个展品\",\"page\":1,\"page_size\":3,\"similarity_threshold\":0.2}"));
    var model = requests.getLast();
    assertThat(model.uri()).isEqualTo("/text/chat/completions?version=1");
    assertThat(model.headers())
        .containsEntry("authorization", "Bearer model-secret")
        .containsEntry("accept", "text/event-stream");
    assertThat(model.body().path("stream").asBoolean()).isTrue();
    assertThat(model.body().path("temperature").asDouble()).isEqualTo(.1);
    assertThat(model.body().path("messages").get(1).path("content").asText()).contains("秦代陶俑");
    assertThat(events.toString())
        .doesNotContain("model-secret", "search-secret", "classifier-secret");
    assertThat(events.getFirst().path("event").asText()).isEqualTo("workflow_started");
    assertThat(events.getLast().path("event").asText()).isEqualTo("end");
    assertThat(
            events
                .get(events.size() - 2)
                .path("data")
                .path("metadata")
                .path("enable_history")
                .asBoolean())
        .isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "70%,text,3",
    "0.7,text,3",
    "0.69999,text_vision,2",
    "1%,text_vision,2",
    "NaN,text_vision,2"
  })
  void imageRoutes(String confidence, String type, int count) {
    handler = r -> r.uri().startsWith("/classifier") ? classification(confidence) : success(r);
    var input = input();
    input.put(
        "image_url", List.of("https://image.test/first.JPG", "https://image.test/second.jpg"));
    input.put("query", "これはなんですか？");
    assertThat(result(run(input)).path("code").asText()).isEqualTo("0");
    assertThat(requests).hasSize(count);
    assertThat(requests.getFirst().body())
        .isEqualTo(
            Json.parse("{\"image_url\":\"https://image.test/first.JPG\",\"image_base64\":\"\"}"));
    assertThat(requests.getFirst().headers()).containsEntry("x-api-key", "classifier-secret");
    assertThat(requests.getLast().uri()).startsWith("/" + type + "/");
    JsonNode content = requests.getLast().body().path("messages").get(1).path("content");
    if (type.equals("text")) {
      assertThat(requests.get(1).body().path("dataset_ids").get(0).asText()).isEqualTo("travel-kb");
      assertThat(requests.get(1).body().path("question").asText()).isEqualTo("检索关于兵马俑的文档");
      assertThat(content.asText()).contains("これはなんですか？", "兵马俑");
    } else
      assertThat(content.get(1).path("image_url").path("url").asText())
          .isEqualTo("https://image.test/first.JPG");
  }

  @Test
  void emptyKnowledgeKeepsCandidateAndDefaultQuestion() {
    handler =
        r -> r.uri().startsWith("/api/v1/retrieval") ? Reply.json(Map.of("code", 0, "data", Map.of("chunks", List.of()))) : success(r);
    var input = input();
    input.put("query", "");
    input.put("image_url", List.of("https://image.test/a.png"));
    assertThat(result(run(input)).path("code").asText()).isEqualTo("0");
    assertThat(requests.getLast().body().path("messages").get(1).path("content").asText())
        .contains("兵马俑", "当前没有可用的内部资料", "请简要介绍这个景点或展品");
  }

  static Stream<Arguments> options() {
    List<Arguments> result = new ArrayList<>();
    List<Map<String, Object>> options =
        List.of(
            Map.of(),
            Map.of("thinking", Map.of()),
            Map.of("thinking", Map.of("type", "enabled", "budget_tokens", 1024)),
            Map.of(
                "chat_template_kwargs",
                Map.of("thinking", false, "other", Map.of("value", List.of(1, true)))),
            Map.of("thinking", "{\"type\":\"disabled\"}"),
            Map.of("chat_template_kwargs", "{\"thinking\":true}"),
            Map.of(
                "thinking",
                Map.of("type", "enabled"),
                "chat_template_kwargs",
                Map.of("thinking", true)));
    for (String type : List.of("text", "text_vision"))
      for (String provider : List.of("openai-compatible", "convaigateway"))
        for (Map<String, Object> option : options) result.add(Arguments.of(type, provider, option));
    return result.stream();
  }

  @ParameterizedTest
  @MethodSource("options")
  void optionsAndBothModelProtocols(String type, String provider, Map<String, Object> options) {
    var model = model(type);
    model.put("provider", provider);
    model.putAll(options);
    if (!provider.equals("openai-compatible"))
      model.put("endpoint", base + "/proxy?endpoint=Latest");
    var input = input();
    input.put("model_config", List.of(model));
    if (type.equals("text_vision")) {
      input.put("image_base64", List.of("data:image/jpeg;base64,/9j/++8="));
      handler = r -> r.uri().startsWith("/classifier") ? classification("1%") : success(r);
    }
    assertThat(result(run(input)).path("code").asText()).isEqualTo("0");
    var captured = requests.getLast();
    JsonNode body = captured.body();
    if (!provider.equals("openai-compatible")) {
      assertThat(captured.uri())
          .isEqualTo("/proxy/runtimes/test-model/invocations/stream?endpoint=Latest");
      assertThat(captured.headers()).doesNotContainKey("authorization");
      assertThat(body.path("temp_token").asText()).isEqualTo("model-secret");
      assertThat(body.path("action").asText())
          .isEqualTo(type.equals("text") ? "invoke_text" : "invoke_text_vision");
      body = body.get("model_request");
    }
    assertThat(body.path("stream").asBoolean()).isTrue();
    for (String field : List.of("thinking", "chat_template_kwargs")) {
      Object option = options.get(field);
      if (option == null) assertThat(body.has(field)).isFalse();
      else
        assertThat(body.get(field))
            .isEqualTo(
                option instanceof String value
                    ? Json.parse(value)
                    : Json.MAPPER.valueToTree(option));
    }
    if (type.equals("text_vision"))
      assertThat(
              body.path("messages")
                  .get(1)
                  .path("content")
                  .get(1)
                  .path("image_url")
                  .path("url")
                  .asText())
          .isEqualTo("data:image/jpeg;base64,/9j/++8=");
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "[]", "null", "bad-json", "{\"value\":NaN}"})
  void invalidOptionsStopBeforeNetwork(String invalid) {
    for (String field : List.of("thinking", "chat_template_kwargs")) {
      var model = model("text");
      model.put(field, invalid);
      var input = input();
      input.put("model_config", List.of(model));
      assertThat(result(run(input)).path("code").asText()).isEqualTo("400");
    }
    assertThat(requests).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"400,400", "401,401", "503,500"})
  void classifierFailuresNeverFallBack(int status, String code) {
    handler = r -> new Reply(status, "application/json", Flux.just("private-upstream-secret"));
    var input = input();
    input.put("image_url", List.of("https://image.test/a.jpg"));
    var events = run(input);
    assertThat(result(events).path("code").asText()).isEqualTo(code);
    assertThat(requests).hasSize(1);
    assertThat(events.toString()).doesNotContain("private-upstream-secret");
  }

  @ParameterizedTest
  @ValueSource(strings = {"http", "malformed", "missing_chunks", "timeout"})
  void searchFailuresStopBeforeModel(String failure) {
    handler =
        r ->
            switch (failure) {
              case "http" -> new Reply(503, "application/json", Flux.just("secret"));
              case "malformed" ->
                  new Reply(200, "application/json", Flux.just("<html>secret</html>"));
              case "timeout" -> new Reply(200, "application/json", Flux.never());
              default -> Reply.json(Map.of("error", "secret"));
            };
    var events = run(input(), settings(Map.of("RAGFLOW_TIMEOUT", "0.2", "RAGFLOW_RETRIES", "0")));
    assertThat(result(events).path("code").asText()).isEqualTo("E5002");
    assertThat(requests).hasSize(1);
    assertThat(events.toString()).doesNotContain("secret");
  }

  @Test
  void missingModelAndHostRestriction() {
    var input = input();
    input.put("image_url", List.of("https://image.test/a.jpg"));
    input.put("model_config", List.of(model("text")));
    handler = r -> classification("1%");
    assertThat(result(run(input)).path("code").asText()).isEqualTo("400");
    assertThat(requests).hasSize(1);
    requests.clear();
    handler = this::success;
    assertThat(
            result(run(input(), settings(Map.of("MODEL_ALLOWED_HOSTS", "approved.test"))))
                .path("code")
                .asText())
        .isEqualTo("400");
    assertThat(requests).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/invocations", "/stream", "/invocations/stream"})
  void httpAliasesHealthAndCapabilities(String path) {
    var settings = settings();
    var client =
        WebTestClient.bindToController(new AgentController(events(settings), settings)).build();
    client
        .get()
        .uri("/ping")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .json("{\"status\":\"Healthy\"}");
    var raw =
        client
            .post()
            .uri(path)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer runtime-secret")
            .bodyValue(Map.of("inputs", Map.of("operation", "query_capabilities")))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith("text/event-stream")
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    var frames = frames(List.of(raw));
    assertThat(messages(frames)).hasSize(1);
    assertThat(result(frames).path("capabilities").path("chat_completions").asBoolean()).isTrue();
    assertThat(requests).isEmpty();
  }

  @Test
  void httpValidationAndInboundCredentials() {
    var settings = settings();
    var client =
        WebTestClient.bindToController(new AgentController(events(settings), settings)).build();
    client.post().uri("/invocations").bodyValue("hello").exchange().expectStatus().isEqualTo(415);
    client
        .post()
        .uri("/invocations")
        .header("Content-Type", "application/json")
        .bodyValue("bad-json")
        .exchange()
        .expectStatus()
        .isBadRequest();
    client
        .post()
        .uri("/invocations")
        .header("Content-Type", "application/json")
        .bodyValue("{} {}")
        .exchange()
        .expectStatus()
        .isBadRequest();
    var small = settings(Map.of("MAX_REQUEST_BYTES", "10"));
    WebTestClient.bindToController(new AgentController(events(small), small))
        .build()
        .post()
        .uri("/invocations")
        .header("Content-Type", "application/json")
        .bodyValue("{\"inputs\":{}}")
        .exchange()
        .expectStatus()
        .isEqualTo(413);
    client
        .post()
        .uri("/invocations")
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer runtime-secret")
        .bodyValue(Map.of("inputs", input()))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .consumeWith(
            r ->
                assertThat(r.getResponseBody())
                    .contains("workflow_finished")
                    .doesNotContain("runtime-secret"));
    assertThat(requests.toString()).doesNotContain("runtime-secret");
  }
}
