package com.travel.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class InputValidationTest extends AgentTestSupport {
  @ParameterizedTest
  @ValueSource(strings = {"null", "[]", "1", "\"text\""})
  void inputsRequireAnObject(String raw) {
    assertThatThrownBy(() -> Inputs.parse(Json.parse(raw)))
        .isInstanceOf(AgentException.class).hasMessage("请求参数格式错误");
  }

  @Test
  void missingInputsObjectIsAnInputError() {
    assertThatThrownBy(() -> Inputs.parse(null))
        .isInstanceOf(AgentException.class).hasMessage("请求参数格式错误");
  }

  @Test
  void unexpectedParserFailureIsNotReportedAsInvalidUserInput() {
    JsonNode raw = mock(JsonNode.class);
    when(raw.isObject()).thenReturn(true);
    IllegalStateException failure = new IllegalStateException("internal parser failure");
    when(raw.path("model_config")).thenThrow(failure);
    assertThatThrownBy(() -> Inputs.parse(raw)).isSameAs(failure);
  }

  @ParameterizedTest
  @CsvSource({"1%,0.01", "0.5%,0.005", "70%,0.7", "72.47,0.7247", "0.9,0.9"})
  void confidenceNormalization(String raw, double expected) {
    var json =
        Json.MAPPER.valueToTree(
            Map.of(
                "code",
                200,
                "data",
                Map.of("top1_result", Map.of("class_name", "test", "confidence", raw))));
    assertThat(ExternalServices.parseClassification(json).confidence()).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"NaN", "inf", "-1", "101%", "broken"})
  void invalidConfidenceDoesNotMatch(String raw) {
    var json =
        Json.MAPPER.valueToTree(
            Map.of(
                "code",
                200,
                "data",
                Map.of("top1_result", Map.of("class_name", "test", "confidence", raw))));
    assertThatThrownBy(() -> ExternalServices.parseClassification(json))
        .hasMessage("图片分类服务分类字段格式错误");
  }

  @ParameterizedTest
  @ValueSource(strings = {"raw", "url_safe", "data"})
  void imageBase64Normalization(String wrapper) {
    byte[] bytes = {(byte) 255, (byte) 216, (byte) 255, (byte) 251, (byte) 239};
    String canonical = Base64.getEncoder().encodeToString(bytes);
    String encoded =
        switch (wrapper) {
          case "url_safe" -> Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
          case "data" -> "data:image/jpeg;base64," + canonical;
          default -> canonical;
        };
    var parsed = Inputs.parse(Json.MAPPER.valueToTree(Map.of("image_base64", List.of(encoded))));
    var image = ImageInput.prepare(parsed, 100);
    assertThat(image.base64()).isEqualTo(canonical);
    assertThat(image.visionUrl()).isEqualTo("data:image/jpeg;base64," + canonical);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"image_url\":[\"ftp://image.test/a.jpg\"]}",
        "{\"image_url\":[\"https://image.test/a.gif\"]}",
        "{\"image_url\":[\"https://image.test/a.jpg\"],\"image_base64\":[\"x\"]}",
        "{\"image_base64\":[\"broken!\"]}",
        "{\"image_base64\":[\"data:image/png;base64\"]}",
        "{\"image_base64\":[\"aGVsbG8=\"]}"
      })
  void invalidImages(String raw) {
    assertThatThrownBy(() -> ImageInput.prepare(Inputs.parse(Json.parse(raw)), 100))
        .isInstanceOf(AgentException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 12, 12000})
  void imageLimitsAndUnicodeQueryLength(int configuredLimit) {
    var parsed = Inputs.parse(Json.parse("{\"image_base64\":[\"/9j/++8=\"]}"));
    assertThatThrownBy(() -> ImageInput.prepare(parsed, 4)).isInstanceOf(AgentException.class);
    var config = settings(configuredLimit == 0 ? Map.of()
        : Map.of("MAX_QUERY_CHARS", String.valueOf(configuredLimit)));
    int limit = configuredLimit == 0 ? 10_000 : configuredLimit;
    var raw = input();
    for (String character : List.of("旅", "😀")) {
      requests.clear();
      String query = character.repeat(limit);
      raw.put("query", query);
      assertThat(result(run(raw, config)).path("code").asText()).isEqualTo("0");
      assertThat(requests.getFirst().body().path("question").asText()).isEqualTo(query);
      assertThat(requests.getLast().body().path("messages").get(1).path("content").asText())
          .contains(query);
      requests.clear();
      raw.put("query", query + character);
      var error = result(run(raw, config));
      assertThat(error.path("code").asText()).isEqualTo("400");
      assertThat(error.path("message").asText()).isEqualTo("文本长度不能大于" + limit + "字");
      assertThatThrownBy(() -> new RagFlowClient(http, config).search(query + character).block())
          .isInstanceOf(AgentException.class).hasMessage("检索内容必须为1至" + limit + "个字符");
      assertThat(requests).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"MAX_QUERY_CHARS", "MAX_RESPONSE_CHARS"})
  void invalidTextLimitsFailAtStartup(String variable) {
    for (String value : List.of("0", "-1", "1.5", "NaN", "2147483648", "invalid-value")) {
      assertThatThrownBy(() -> settings(Map.of(variable, value)))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(variable);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"create_response", "fetch_response", "unknown"})
  void unsupportedOperationsNeverCallServices(String operation) {
    var raw = input();
    raw.put("operation", operation);
    assertThat(result(run(raw)).path("code").asText()).isEqualTo("400");
    assertThat(requests).isEmpty();
  }

  @Test
  void nullOrAbsentOptionsAndOperation() {
    var model = model("text");
    model.put("thinking", null);
    var raw = input();
    raw.put("operation", null);
    raw.put("model_config", List.of(model));
    assertThat(result(run(raw)).path("code").asText()).isEqualTo("0");
    assertThat(requests.getLast().body().has("thinking")).isFalse();
  }

  @Test
  void invalidConfigurationIsRejectedWithoutExposingSecret() {
    for (Object invalid : List.of(true, List.of())) {
      var model = model("text");
      model.put("thinking", invalid);
      var raw = input();
      raw.put("model_config", List.of(model));
      assertThat(result(run(raw)).path("code").asText()).isEqualTo("400");
    }
    for (String field : List.of("temperature", "endpoint", "auth_token")) {
      var model = model("text");
      model.put(field, field.equals("auth_token") ? "model-secret\n" : "model-secret");
      var raw = input();
      raw.put("model_config", List.of(model));
      var events = run(raw);
      assertThat(result(events).path("code").asText()).isEqualTo("400");
      assertThat(events.toString()).doesNotContain("model-secret");
    }
    assertThat(requests).isEmpty();
  }

  @Test
  void tokenFileIsRereadAndThresholdPreserved(@TempDir Path directory) throws Exception {
    Path token = directory.resolve("token");
    Files.writeString(token, "first-token");
    var settings =
        settings(
            Map.of(
                "RAGFLOW_API_TOKEN_FILE",
                token.toString(),
                "RAGFLOW_SIMILARITY_THRESHOLD",
                "0.25",
                "RAGFLOW_URL",
                base + "/api/v1/retrieval?env=prod"));
    var client = new ExternalServices(http, settings);
    client.search("问题").block();
    Files.writeString(token, "second-token\n");
    client.search("问题").block();
    assertThat(requests.stream().map(r -> r.headers().get("authorization")).toList())
        .containsExactly("Bearer first-token", "Bearer second-token");
    assertThat(requests.getFirst().uri()).isEqualTo("/api/v1/retrieval?env=prod");
    assertThat(requests.getFirst().headers()).doesNotContainKey("x-apig-appcode");
    assertThat(requests.getFirst().body().path("similarity_threshold").asDouble()).isEqualTo(.25);
  }

  @Test
  void proxyPathsPreserveQueryParameters() {
    for (String path : List.of("/invocations", "/invocations/stream")) {
      var model = model("text");
      model.put("endpoint", base + path + "?version=1");
      assertThat(CompatibleChatModel.proxyEndpoint(config(model)).toString())
          .isEqualTo(base + "/invocations/stream?version=1");
    }
  }

  @Test
  void missingClassifierKeyDoesNotCallNetwork() {
    var raw = input();
    raw.put("image_url", List.of("https://image.test/a.jpg"));
    assertThat(result(run(raw, settings(Map.of("CLASSIFIER_API_KEY", "")))).path("code").asText())
        .isEqualTo("E5004");
    assertThat(requests).isEmpty();
  }

  @Test
  void missingDatasetIdDoesNotCallNetwork() {
    assertThat(
            result(run(input(), settings(Map.of("RAGFLOW_DATASET_ID", ""))))
                .path("code")
                .asText())
        .isEqualTo("E5004");
    assertThat(requests).isEmpty();
  }
}
