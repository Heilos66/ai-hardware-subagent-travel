package com.heilos.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class WorkflowTimingTest extends AgentTestSupport {
  private final List<String> logs = new CopyOnWriteArrayList<>();
  private final List<String> ttftLogs = new CopyOnWriteArrayList<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(WorkflowTiming.class);
  private Level previousLevel;
  private boolean previousAdditive;
  private AppenderBase<ILoggingEvent> capture;

  @BeforeEach
  void captureTiming() {
    previousLevel = logger.getLevel();
    previousAdditive = logger.isAdditive();
    logger.setLevel(Level.INFO);
    logger.setAdditive(false);
    capture =
        new AppenderBase<>() {
          @Override
          protected void append(ILoggingEvent event) {
            String line = event.getFormattedMessage();
            (line.startsWith("workflow_ttft ") ? ttftLogs : logs).add(line);
          }
        };
    capture.start();
    logger.addAppender(capture);
  }

  @AfterEach
  void restoreLogging() {
    logger.detachAppender(capture);
    capture.stop();
    logger.setLevel(previousLevel);
    logger.setAdditive(previousAdditive);
  }

  @Test
  void defaultClassifierAndRagflowSettings() {
    var config = new AgentSettings(new MockEnvironment());
    assertThat(config.classifierUrl).isEqualTo("http://1.95.152.66:16000/mobilenetv3large");
    assertThat(config.classifierThreshold).isEqualTo(.7);
    assertThat(config.ragflow).isNotNull();
    assertThat(config.ragflow.timeout()).isEqualTo(Duration.ofSeconds(10));
  }

  @ParameterizedTest
  @CsvSource({
    "none,0.7,search_text,text,2",
    "image,0.7,search_image,text,3",
    "image,0.69999,none,text_vision,2"
  })
  void ragflowRoutesStreamingAndCorrelatedStageTimes(
      String image, String confidence, String search, String model, int calls) {
    handler = r -> {
      Reply reply = r.uri().startsWith("/classifier") ? classification(confidence) : success(r);
      return new Reply(reply.status(), reply.type(), reply.body().delaySubscription(Duration.ofMillis(30)));
    };
    var input = input();
    input.put("query", "private-query-42");
    if (image.equals("image")) input.put("image_url", List.of("https://image.test/private.jpg"));
    var events = run(input, settings());
    assertThat(result(events).path("code").asText()).isEqualTo("0");
    assertThat(requests).hasSize(calls);
    assertThat(requests.getLast().uri()).startsWith("/" + model + "/");
    assertThat(
            messages(events).stream()
                .map(e -> e.path("text").asText())
                .collect(Collectors.joining()))
        .isEqualTo(result(events).path("message").asText());
    String executionId =
        events.stream()
            .filter(e -> e.path("event").asText().equals("workflow_finished"))
            .findFirst()
            .orElseThrow()
            .path("data")
            .path("execution_id")
            .asText();
    awaitTotals(1);
    assertThat(logs)
        .allMatch(line -> line.contains("execution_id=" + executionId))
        .allMatch(line -> line.contains("status=succeeded") && line.contains("search_mode=ragflow"));
    var stages = logs.stream().map(line -> field(line, "stage")).toList();
    if (search.equals("none"))
      assertThat(stages).containsExactly("validate", "classify", "model", "total");
    else {
      assertThat(stages)
          .containsExactlyElementsOf(
              image.equals("image")
                  ? List.of("validate", "classify", search, "model", "total")
                  : List.of("validate", search, "model", "total"));
      assertThat(
              Double.parseDouble(
                  field(
                      logs.stream()
                          .filter(l -> field(l, "stage").equals(search))
                          .findFirst()
                          .orElseThrow(),
                      "elapsed_ms")))
          .isGreaterThanOrEqualTo(0);
      assertThat(requests.getLast().body().path("messages").get(1).path("content").asText())
          .contains("秦代陶俑");
    }
    assertThat(logs.toString())
        .doesNotContain(
            "private-query-42", "model-secret", "search-secret", "classifier-secret", "https://image.test");
    assertThat(ttftLogs).hasSize(1);
    String ttft = ttftLogs.getFirst();
    assertThat(field(ttft, "execution_id")).isEqualTo(executionId);
    assertThat(field(ttft, "first_token_type")).isEqualTo("reasoning_content");
    assertThat(field(ttft, "response_mode")).isEqualTo("sse");
    assertTtftComponents(ttft, logs);
    assertThat(milliseconds(ttft, "model_to_first_token_ms")).isGreaterThanOrEqualTo(25);
    if (!search.equals("none"))
      assertThat(milliseconds(ttft, search + "_ms")).isGreaterThanOrEqualTo(25);
    if (image.equals("image"))
      assertThat(milliseconds(ttft, "classify_ms")).isGreaterThanOrEqualTo(25);
    assertThat(ttftLogs.toString()).doesNotContain(
        "private-query-42", "model-secret", "search-secret", "classifier-secret", "https://image.test");
  }

  @Test
  void classifierFailureRecordsFailedStageAndTotalWithoutModelCall() {
    handler = r -> new Reply(503, "application/json", Flux.just("private-upstream-secret"));
    var input = input();
    input.put("image_url", List.of("https://image.test/a.jpg"));
    assertThat(result(run(input, settings())).path("code").asText()).isEqualTo("500");
    awaitTotals(1);
    assertThat(logs.stream().map(l -> field(l, "stage") + ":" + field(l, "status")))
        .containsExactly("validate:succeeded", "classify:failed", "total:failed");
    assertThat(requests).hasSize(1);
    assertThat(logs.toString()).doesNotContain("private-upstream-secret");
    assertThat(ttftLogs).isEmpty();
  }

  @Test
  void cancellationDuringRetrievalRecordsCancelledStageAndTotal() {
    handler = r -> new Reply(200, "application/json", Flux.never());
    StepVerifier.create(events(settings()).stream(payload(input())))
        .expectNextCount(3)
        .thenAwait(Duration.ofMillis(10))
        .then(() -> await().atMost(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(requests).hasSize(1)))
        .thenCancel()
        .verify(Duration.ofSeconds(3));
    awaitTotals(1);
    assertThat(logs.stream().map(l -> field(l, "stage") + ":" + field(l, "status")))
        .containsExactly("validate:succeeded", "search_text:cancelled", "total:cancelled");
    assertThat(requests).hasSize(1);
    assertThat(ttftLogs).isEmpty();
  }

  @Test
  void concurrentRequestsHaveIndependentExecutionIdsAndOneTotalEach() {
    var workflow = events(settings());
    var completed =
        Flux.range(0, 4)
            .flatMap(i -> workflow.stream(payload(input())).collectList())
            .collectList()
            .block(Duration.ofSeconds(5));
    assertThat(completed).hasSize(4);
    awaitTotals(4);
    var grouped = logs.stream().collect(Collectors.groupingBy(l -> field(l, "execution_id")));
    assertThat(grouped).hasSize(4);
    assertThat(ttftLogs).hasSize(4);
    assertThat(ttftLogs.stream().map(l -> field(l, "execution_id")))
        .containsExactlyInAnyOrderElementsOf(grouped.keySet());
    ttftLogs.forEach(line -> assertTtftComponents(line, grouped.get(field(line, "execution_id"))));
    grouped
        .values()
        .forEach(
            lines ->
                assertThat(lines.stream().map(l -> field(l, "stage")))
                    .containsExactly("validate", "search_text", "model", "total"));
  }

  @Test
  void brokenLogAppenderCannotFailTheWorkflow() {
    var broken =
        new AppenderBase<ILoggingEvent>() {
          @Override
          public synchronized void doAppend(ILoggingEvent event) {
            throw new IllegalStateException("logging unavailable");
          }

          @Override
          protected void append(ILoggingEvent event) {}
        };
    broken.start();
    logger.addAppender(broken);
    try {
      assertThat(result(run(input(), settings())).path("code").asText()).isEqualTo("0");
      awaitTotals(1);
      assertThat(ttftLogs).hasSize(1);
    } finally {
      logger.detachAppender(broken);
      broken.stop();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"content", "reasoning_content", "content_and_reasoning"})
  void firstRealTokenLogsImmediatelyBeforeStreamCompletes(String type) {
    Sinks.One<Void> first = Sinks.one(), tail = Sinks.one();
    AtomicBoolean heartbeatSeen = new AtomicBoolean(), firstSeen = new AtomicBoolean();
    String text = type.equals("reasoning_content") ? "" : "首个正文";
    String reasoning = type.equals("content") ? "" : "首个思考";
    String ignored = ": keep-alive\n\n"
        + "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n"
        + delta("", "") + "data: {\"choices\":[],\"usage\":{}}\n\n";
    handler = r -> r.uri().contains("retrieval") ? success(r)
        : new Reply(200, "text/event-stream", Flux.concat(Flux.just(ignored),
            first.asMono().thenMany(Flux.just(delta(text, reasoning))),
            tail.asMono().thenMany(Flux.just(delta("后续正文", "后续思考") + DONE))));
    try {
      var received = events(settings(Map.of("HEARTBEAT_SECONDS", "0.02")))
          .stream(payload(input()))
          .doOnNext(frame -> {
            if (frame.startsWith(":") && heartbeatSeen.compareAndSet(false, true)) {
              assertThat(ttftLogs).isEmpty();
              first.tryEmitEmpty();
            }
            if (frame.startsWith("event: message") && firstSeen.compareAndSet(false, true)) {
              assertThat(ttftLogs).hasSize(1);
              assertThat(field(ttftLogs.getFirst(), "first_token_type")).isEqualTo(type);
              assertThat(logs).noneMatch(line -> line.contains(" stage=model ") || line.contains(" stage=total "));
              tail.tryEmitEmpty();
            }
          }).collectList().block(Duration.ofSeconds(3));
      assertThat(heartbeatSeen).isTrue();
      assertThat(firstSeen).isTrue();
      assertThat(result(frames(received)).path("code").asText()).isEqualTo("0");
      assertThat(ttftLogs).hasSize(1);
      awaitTotals(1);
      assertTtftComponents(ttftLogs.getFirst(), logs);
    } finally {
      first.tryEmitEmpty();
      tail.tryEmitEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"http", "empty", "invalid", "timeout", "capabilities"})
  void noTtftWithoutModelContent(String scenario) {
    handler = r -> r.uri().contains("retrieval") ? success(r) : switch (scenario) {
      case "http" -> new Reply(503, "application/json", Flux.just("{}"));
      case "empty" -> Reply.sse(delta("", "") + DONE);
      case "invalid" -> Reply.sse("data: invalid\n\n");
      default -> new Reply(200, "text/event-stream", Flux.never());
    };
    var raw = input();
    if (scenario.equals("capabilities")) raw.put("operation", "query_capabilities");
    var received = run(raw, settings(Map.of("MODEL_TIMEOUT", "0.1")));
    if (!scenario.equals("capabilities"))
      assertThat(result(received).path("code").asText()).isEqualTo("E5003");
    awaitTotals(1);
    assertThat(ttftLogs).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cancellationKeepsTtftOnlyIfATokenWasReceived(boolean tokenReceived) {
    handler = r -> r.uri().contains("retrieval") ? success(r)
        : new Reply(200, "text/event-stream", Flux.concat(
            Flux.just(tokenReceived ? delta("首个正文", "") : delta("", "")), Flux.never()));
    StepVerifier.create(events(settings(Map.of("HEARTBEAT_SECONDS", "0.02")))
            .stream(payload(input())).filter(frame -> frame.startsWith(":")
                && requests.stream().anyMatch(r -> r.uri().contains("/chat/completions"))
                && (!tokenReceived || !ttftLogs.isEmpty())))
        .expectNext(": keep-alive\n\n")
        .thenCancel().verify(Duration.ofSeconds(3));
    awaitTotals(1);
    assertThat(ttftLogs).hasSize(tokenReceived ? 1 : 0);
    assertThat(logs).anyMatch(line -> line.contains(" stage=model status=cancelled "));
  }

  @Test
  void failureAfterFirstTokenRetainsTtft() {
    handler = r -> r.uri().contains("retrieval") ? success(r) : Reply.sse(delta("部分正文", ""));
    assertThat(result(run(input())).path("code").asText()).isEqualTo("E5003");
    awaitTotals(1);
    assertThat(ttftLogs).hasSize(1);
    assertThat(logs).anyMatch(line -> line.contains(" stage=model status=failed "));
    assertTtftComponents(ttftLogs.getFirst(), logs);
  }

  @Test
  void jsonFallbackIsMarkedAsWholeResponseTiming() {
    handler = r -> r.uri().contains("retrieval") ? success(r) : Reply.json(Map.of(
        "choices", List.of(Map.of("message", Map.of("content", "完整正文", "reasoning_content", "思考")))));
    assertThat(result(run(input())).path("code").asText()).isEqualTo("0");
    awaitTotals(1);
    assertThat(ttftLogs).hasSize(1);
    assertThat(field(ttftLogs.getFirst(), "response_mode")).isEqualTo("json");
    assertTtftComponents(ttftLogs.getFirst(), logs);
  }

  private static void assertTtftComponents(String ttft, List<String> stageLogs) {
    double sum = 0;
    for (String stage : List.of("validate", "classify", "search_text", "search_image")) {
      double elapsed = stageLogs.stream().filter(line -> field(line, "stage").equals(stage))
          .mapToDouble(line -> milliseconds(line, "elapsed_ms")).sum();
      assertThat(milliseconds(ttft, stage + "_ms")).isEqualTo(elapsed);
      sum += elapsed;
    }
    double modelFirst = milliseconds(ttft, "model_to_first_token_ms");
    double modelTotal = stageLogs.stream().filter(line -> field(line, "stage").equals("model"))
        .mapToDouble(line -> milliseconds(line, "elapsed_ms")).sum();
    assertThat(modelFirst).isBetween(0.0, modelTotal);
    assertThat(milliseconds(ttft, "stages_sum_ms"))
        .isCloseTo(sum + modelFirst, org.assertj.core.data.Offset.offset(0.004));
    assertThat(milliseconds(ttft, "overhead_ms")).isGreaterThanOrEqualTo(0);
    assertThat(milliseconds(ttft, "ttft_ms"))
        .isCloseTo(milliseconds(ttft, "stages_sum_ms") + milliseconds(ttft, "overhead_ms"),
            org.assertj.core.data.Offset.offset(0.002));
  }

  private static double milliseconds(String line, String name) {
    return Double.parseDouble(field(line, name));
  }

  private void awaitTotals(int count) {
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThat(logs.stream().filter(l -> l.contains(" stage=total ")).count())
                    .isEqualTo(count));
  }

  private static String field(String line, String name) {
    return line.substring(line.indexOf(name + "=") + name.length() + 1).split(" ")[0];
  }
}
