package com.heilos.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

class WorkflowDiagnosticsTest extends AgentTestSupport {
  record Diagnostic(String executionId, String event, boolean truncated, JsonNode data) {}

  private final List<String> logs = new CopyOnWriteArrayList<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(WorkflowDiagnostics.class);
  private Level previousLevel;
  private boolean previousAdditive;
  private AppenderBase<ILoggingEvent> capture;

  @BeforeEach
  void captureDiagnostics() {
    previousLevel = logger.getLevel();
    previousAdditive = logger.isAdditive();
    logger.setLevel(Level.INFO);
    logger.setAdditive(false);
    capture = new AppenderBase<>() {
      @Override
      protected void append(ILoggingEvent event) {
        logs.add(event.getFormattedMessage());
      }
    };
    capture.start();
    logger.addAppender(capture);
  }

  @AfterEach
  void restoreDiagnostics() {
    logger.detachAppender(capture);
    capture.stop();
    logger.setLevel(previousLevel);
    logger.setAdditive(previousAdditive);
  }

  private AgentSettings detailed() {
    return settings(Map.of("RAGFLOW_LOG_CONTENT", "true", "MODEL_LOG_REQUEST", "true"));
  }

  private List<Diagnostic> diagnostics() {
    var pattern = Pattern.compile(
        "workflow_diagnostic execution_id=(\\S+) event=(\\S+) truncated=(true|false) data=(.*)");
    return logs.stream().map(line -> {
      var match = pattern.matcher(line);
      assertThat(match.matches()).isTrue();
      return new Diagnostic(match.group(1), match.group(2), Boolean.parseBoolean(match.group(3)),
          Json.parse(match.group(4)));
    }).toList();
  }

  private Diagnostic event(String name) {
    return diagnostics().stream().filter(log -> log.event().equals(name)).findFirst().orElseThrow();
  }

  private static String executionId(List<JsonNode> events) {
    return events.stream().filter(e -> e.path("event").asText().equals("workflow_finished"))
        .findFirst().orElseThrow().path("data").path("execution_id").asText();
  }

  private static Reply retrieved(List<?> chunks) {
    return Reply.json(Map.of("code", 0, "data", Map.of("chunks", chunks)));
  }

  @Test
  void defaultLogsSummarizeResultsAndCorrelateWithoutBodies() {
    var events = run(input());
    assertThat(result(events).path("code").asText()).isEqualTo("0");
    assertThat(diagnostics()).extracting(Diagnostic::event)
        .containsExactly("ragflow_result", "model_request");
    assertThat(diagnostics()).allMatch(log -> log.executionId().equals(executionId(events)));
    var result = event("ragflow_result").data();
    assertThat(result.path("returned_chunks").asInt()).isEqualTo(1);
    assertThat(result.path("kept_chunks").asInt()).isEqualTo(1);
    assertThat(result.path("empty_result").asBoolean()).isFalse();
    assertThat(result.path("selected").get(0).path("similarity").asDouble()).isEqualTo(.88);
    assertThat(result.path("selected").get(0).path("document").asText()).isEqualTo("陶俑");
    assertThat(event("model_request").data().path("body_logged").asBoolean()).isFalse();
    assertThat(event("model_request").data().has("body")).isFalse();
    assertThat(logs.toString()).doesNotContain(
        "介绍这个展品", "秦代陶俑", "model-secret", "search-secret", "classifier-secret");
  }

  @ParameterizedTest
  @ValueSource(strings = {"openai-compatible", "convaigateway"})
  void detailedModelBodyMatchesTheActualRequestWithCredentialsRedacted(String provider) {
    var model = model("text");
    model.put("provider", provider);
    model.put("thinking", Map.of("type", "disabled", "api_key", "nested-secret"));
    var input = input();
    input.put("model_config", List.of(model));
    var events = run(input, detailed());
    assertThat(result(events).path("code").asText()).isEqualTo("0");
    JsonNode sent = requests.getLast().body();
    ObjectNode expected = sent.deepCopy();
    JsonNode expectedRequest = expected;
    if (provider.equals("convaigateway")) {
      expected.put("temp_token", "<REDACTED>");
      expectedRequest = expected.path("model_request");
      assertThat(sent.path("temp_token").asText()).isEqualTo("model-secret");
    }
    ((ObjectNode) expectedRequest.path("thinking")).put("api_key", "<REDACTED>");
    var logged = event("model_request");
    assertThat(logged.truncated()).isFalse();
    assertThat(logged.data().path("body")).isEqualTo(expected);
    assertThat(event("ragflow_content").data().path("chunks").get(0).path("content").asText())
        .isEqualTo("秦代陶俑");
    assertThat(logged.data().toString()).contains("秦代陶俑", "介绍这个展品", "stream");
    assertThat(logs.toString()).doesNotContain(
        "nested-secret", "model-secret", "search-secret", "classifier-secret");
  }

  @Test
  void resultCountsDistinguishReturnedAndFilteredChunks() {
    handler = r -> r.uri().contains("retrieval") ? retrieved(List.of(
        Map.of("id", "low", "content", "低分", "similarity", .1),
        Map.of("id", "blank", "content", " ", "similarity", .9),
        Map.of("id", "kept", "content", "可信资料", "similarity", .3))) : success(r);
    assertThat(result(run(input(), detailed())).path("code").asText()).isEqualTo("0");
    var summary = event("ragflow_result").data();
    assertThat(summary.path("returned_chunks").asInt()).isEqualTo(3);
    assertThat(summary.path("kept_chunks").asInt()).isEqualTo(1);
    assertThat(summary.path("selected").get(0).path("id").asText()).isEqualTo("kept");
    assertThat(event("ragflow_content").data().path("chunks")).hasSize(3);
    assertThat(event("model_request").data().toString()).contains("可信资料").doesNotContain("低分");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void emptyResultsAreExplicitlyDifferentFromFailures(boolean filtered) {
    handler = r -> r.uri().contains("retrieval") ? retrieved(filtered
        ? List.of(Map.of("content", "低分", "similarity", .1)) : List.of()) : success(r);
    assertThat(result(run(input())).path("code").asText()).isEqualTo("0");
    var summary = event("ragflow_result").data();
    assertThat(summary.path("status").asText()).isEqualTo("succeeded");
    assertThat(summary.path("empty_result").asBoolean()).isTrue();
    assertThat(summary.path("kept_chunks").asInt()).isZero();
    assertThat(diagnostics()).noneMatch(log -> log.event().equals("ragflow_failed"));
  }

  @Test
  void failureLogsIdentifyMissingConfigurationWithoutLeakingValues() {
    var events = run(input(), settings(Map.of("RAGFLOW_URL", "")));
    assertThat(result(events).path("code").asText()).isEqualTo("E5004");
    var failed = event("ragflow_failed");
    assertThat(failed.executionId()).isEqualTo(executionId(events));
    assertThat(failed.data().path("attempts").asInt()).isZero();
    assertThat(failed.data().path("url_configured").asBoolean()).isFalse();
    assertThat(failed.data().path("dataset_id_valid").asBoolean()).isTrue();
    assertThat(failed.data().path("reason").asText()).contains("RAGFLOW_URL");
    assertThat(requests).isEmpty();
    assertThat(diagnostics()).hasSize(1);
    assertThat(logs.toString()).doesNotContain("search-secret", "classifier-secret");
  }

  @Test
  void httpFailuresDoNotLogTheUpstreamBody() {
    handler = r -> new Reply(401, "application/json", Flux.just("private-upstream-error"));
    assertThat(result(run(input(), detailed())).path("code").asText()).isEqualTo("E5002");
    assertThat(event("ragflow_failed").data().path("reason").asText()).contains("HTTP 401");
    assertThat(event("ragflow_failed").data().path("attempts").asInt()).isEqualTo(1);
    assertThat(logs.toString()).doesNotContain("private-upstream-error");
  }

  @Test
  void successfulRetryReportsAttemptsOnce() {
    AtomicInteger attempts = new AtomicInteger();
    handler = r -> r.uri().contains("retrieval") && attempts.incrementAndGet() < 3
        ? new Reply(503, "application/json", Flux.just("private-upstream-error")) : success(r);
    assertThat(result(run(input())).path("code").asText()).isEqualTo("0");
    assertThat(event("ragflow_result").data().path("attempts").asInt()).isEqualTo(3);
    assertThat(diagnostics()).extracting(Diagnostic::event)
        .containsExactly("ragflow_result", "model_request");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void visualRequestsOmitBothImageUrlsAndBase64(boolean base64) {
    handler = r -> r.uri().equals("/classifier") ? classification("1%") : success(r);
    var input = input();
    input.put(base64 ? "image_base64" : "image_url", List.of(base64 ? "/9j/++8="
        : "https://image.test/private.jpg?signature=private-image-signature"));
    assertThat(result(run(input, detailed())).path("code").asText()).isEqualTo("0");
    assertThat(diagnostics()).extracting(Diagnostic::event).containsExactly("model_request");
    assertThat(event("model_request").data().path("body").path("messages").get(1)
        .path("content").get(1).path("image_url").asText()).isEqualTo("<IMAGE_OMITTED>");
    assertThat(logs.toString()).doesNotContain("/9j/++8=", "private-image-signature", "image.test");
    assertThat(requests.getLast().body().path("messages").get(1).path("content").get(1)
        .path("image_url").path("url").asText()).isNotEqualTo("<IMAGE_OMITTED>");
  }

  @Test
  void truncatedLogsRemainSingleLineValidJsonAndDoNotModifyRequests() {
    var input = input();
    input.put("query", "问题\n".repeat(150) + "model-secret");
    var config = settings(Map.of("MODEL_LOG_REQUEST", "true", "DIAGNOSTIC_LOG_MAX_CHARS", "256"));
    assertThat(result(run(input, config)).path("code").asText()).isEqualTo("0");
    var logged = event("model_request");
    assertThat(logged.truncated()).isTrue();
    assertThat(Json.length(logged.data().path("preview").asText())).isEqualTo(256);
    assertThat(logged.data().path("original_chars").asInt()).isGreaterThan(256);
    assertThat(logs).noneMatch(line -> line.contains("\n"));
    assertThat(logs.toString()).doesNotContain("model-secret");
    assertThat(requests.getLast().body().toString()).contains("model-secret");
  }

  @Test
  void rotatingFileTokenIsRedactedFromRetrievalDiagnostics(@TempDir Path directory) throws Exception {
    Path token = directory.resolve("token");
    Files.writeString(token, "mounted-secret\n");
    handler = r -> r.uri().contains("retrieval") ? retrieved(List.of(Map.of(
        "content", "test mounted-secret", "document_keyword", "mounted-secret", "similarity", .8)))
        : success(r);
    var config = settings(Map.of("RAGFLOW_API_TOKEN_FILE", token.toString(),
        "RAGFLOW_LOG_CONTENT", "true", "MODEL_LOG_REQUEST", "true"));
    assertThat(result(run(input(), config)).path("code").asText()).isEqualTo("0");
    assertThat(logs.toString()).doesNotContain("mounted-secret", token.toString());
    assertThat(event("ragflow_content").data().toString()).contains("<REDACTED>");
  }

  @Test
  void concurrentWorkflowsKeepPayloadsAndExecutionIdsTogether() {
    var workflow = events(detailed());
    var executions = Flux.range(0, 6).flatMap(i -> {
      var input = input();
      input.put("query", "问题编号" + i);
      return workflow.stream(payload(input)).collectList()
          .map(frames -> Map.entry("问题编号" + i, executionId(frames(frames))));
    }).collectList().block(Duration.ofSeconds(5));
    assertThat(executions).hasSize(6);
    for (var execution : executions) {
      var related = diagnostics().stream()
          .filter(log -> log.executionId().equals(execution.getValue())).toList();
      assertThat(related).extracting(Diagnostic::event)
          .containsExactly("ragflow_result", "ragflow_content", "model_request");
      assertThat(related.get(1).data().path("question").asText()).isEqualTo(execution.getKey());
      assertThat(related.get(2).data().toString()).contains(execution.getKey());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void brokenAppenderDoesNotFailTheWorkflow(boolean retrievalEnabled) {
    var broken = new AppenderBase<ILoggingEvent>() {
      @Override
      public synchronized void doAppend(ILoggingEvent event) {
        throw new IllegalStateException("log sink unavailable");
      }
      @Override
      protected void append(ILoggingEvent event) {}
    };
    broken.start();
    logger.addAppender(broken);
    try {
      var config = settings(Map.of("RAGFLOW_ENABLED", Boolean.toString(retrievalEnabled),
          "RAGFLOW_LOG_CONTENT", "true", "MODEL_LOG_REQUEST", "true"));
      assertThat(result(run(input(), config)).path("code").asText()).isEqualTo("0");
      assertThat(requests).hasSize(retrievalEnabled ? 2 : 1);
    } finally {
      logger.detachAppender(broken);
      broken.stop();
    }
  }

  @Test
  void startupReportsPresenceAndDefaultRetrievalOptions() {
    var config = settings(Map.of("RAGFLOW_URL", "", "RAGFLOW_DATASET_ID", ""));
    new WorkflowDiagnostics(config).runtimeConfig();
    assertThat(event("ragflow_config").data().path("enabled").asBoolean()).isTrue();
    assertThat(event("ragflow_config").data().path("url_configured").asBoolean()).isFalse();
    assertThat(event("ragflow_config").data().path("dataset_id_valid").asBoolean()).isFalse();
    assertThat(event("ragflow_config").data().path("use_rerank").asBoolean()).isTrue();
    assertThat(event("ragflow_config").data().path("rerank_enabled").asBoolean()).isFalse();
    assertThat(event("ragflow_config").data().path("knn_top_k").asInt()).isEqualTo(64);
    assertThat(event("ragflow_config").data().path("knn_num_candidates").asInt()).isEqualTo(128);
    assertThat(event("ragflow_config").data().path("rerank_candidates_count").isNull()).isTrue();
    assertThat(logs.toString()).doesNotContain("search-secret", "classifier-secret");
  }

  @Test
  void disabledRetrievalIsLoggedAsSkippedRatherThanAnEmptyRemoteResult() {
    var config = settings(Map.of("RAGFLOW_ENABLED", "false", "RAGFLOW_LOG_CONTENT", "true"));
    new WorkflowDiagnostics(config).runtimeConfig();
    var events = run(input(), config);
    assertThat(result(events).path("code").asText()).isEqualTo("0");
    assertThat(event("ragflow_config").data().path("enabled").asBoolean()).isFalse();
    assertThat(diagnostics()).extracting(Diagnostic::event)
        .containsExactly("ragflow_config", "ragflow_skipped", "model_request");
    var skipped = event("ragflow_skipped");
    assertThat(skipped.executionId()).isEqualTo(executionId(events));
    assertThat(skipped.data().path("status").asText()).isEqualTo("skipped");
    assertThat(skipped.data().path("reason").asText()).isEqualTo("RAGFLOW_ENABLED=false");
    assertThat(skipped.data().path("attempts").asInt()).isZero();
    assertThat(skipped.data().path("kept_chunks").asInt()).isZero();
    assertThat(logs.toString()).doesNotContain("介绍这个展品", "search-secret", "model-secret");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void startupRetrievalOptionsMatchFieldsActuallySent(boolean rerank) {
    var config = settings(Map.of("RAGFLOW_USE_RERANK", Boolean.toString(rerank),
        "RAGFLOW_RERANK_ID", "private-reranker-name", "RAGFLOW_KNN_TOP_K", "null",
        "RAGFLOW_KNN_NUM_CANDIDATES", "256", "RAGFLOW_RERANK_CANDIDATES_COUNT", "48"));
    new WorkflowDiagnostics(config).runtimeConfig();
    assertThat(result(run(input(), config)).path("code").asText()).isEqualTo("0");
    var logged = event("ragflow_config").data();
    var sent = requests.getFirst().body();
    assertThat(logged.path("rerank_enabled").asBoolean()).isEqualTo(sent.has("rerank_id"));
    for (String field : List.of("knn_top_k", "knn_num_candidates", "rerank_candidates_count")) {
      if (sent.has(field)) assertThat(logged.path(field)).isEqualTo(sent.path(field));
      else assertThat(logged.path(field).isNull()).isTrue();
    }
    assertThat(logs.toString()).doesNotContain("private-reranker-name", "search-secret", "classifier-secret");
  }

  @ParameterizedTest
  @CsvSource({"RAGFLOW_LOG_CONTENT,yes", "MODEL_LOG_REQUEST,1", "DIAGNOSTIC_LOG_MAX_CHARS,65537"})
  void invalidDiagnosticConfigurationFailsClearly(String key, String value) {
    assertThatThrownBy(() -> settings(Map.of(key, value)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
  }
}
