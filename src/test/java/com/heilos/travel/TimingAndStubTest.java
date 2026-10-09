package com.heilos.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
import reactor.test.StepVerifier;

class TimingAndStubTest extends AgentTestSupport {
  private final List<String> logs = new CopyOnWriteArrayList<>();
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
            logs.add(event.getFormattedMessage());
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

  private AgentSettings stub() {
    return settings(
        Map.of(
            "KOOSEARCH_MODE", "stub",
            "KOOSEARCH_URL", "not configured yet",
            "KOOSEARCH_ENDPOINT", "not configured yet",
            "KOOSEARCH_TEXT_REPO_ID", "",
            "KOOSEARCH_IMAGE_REPO_ID", "",
            "KOOSEARCH_APPCODE", "",
            "KOOSEARCH_TOKEN", "",
            "KOOSEARCH_TOKEN_FILE", "/unavailable/knowledge-token"));
  }

  @Test
  void defaultAddressAndStubMode() {
    var config = new AgentSettings(new MockEnvironment());
    assertThat(config.classifierUrl).isEqualTo("http://1.95.152.66:16000/mobilenetv3large");
    assertThat(config.classifierThreshold).isEqualTo(.7);
    assertThat(config.searchMode).isEqualTo("stub");
    assertThatThrownBy(
            () -> new AgentSettings(new MockEnvironment().withProperty("KOOSEARCH_MODE", "typo")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void stubWaitsExactly150msWithoutBlockingOrKnowledgeConfiguration(boolean image) {
    StepVerifier.withVirtualTime(() -> new ExternalServices(http, stub()).search("问题", image))
        .expectSubscription()
        .expectNoEvent(Duration.ofMillis(149))
        .thenAwait(Duration.ofMillis(1))
        .assertNext(chunks -> assertThat(chunks).isEmpty())
        .verifyComplete();
    assertThat(requests).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "none,0.7,search_text,text,1",
    "image,0.7,search_image,text,2",
    "image,0.69999,none,text_vision,2"
  })
  void stubPreservesRoutesStreamingAndCorrelatedStageTimes(
      String image, String confidence, String search, String model, int calls) {
    handler = r -> r.uri().startsWith("/classifier") ? classification(confidence) : success(r);
    var input = input();
    input.put("query", "private-query-42");
    if (image.equals("image")) input.put("image_url", List.of("https://image.test/private.jpg"));
    var events = run(input, stub());
    assertThat(result(events).path("code").asText()).isEqualTo("0");
    assertThat(requests).hasSize(calls).allMatch(r -> !r.uri().startsWith("/search"));
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
        .allMatch(line -> line.contains("status=succeeded") && line.contains("search_mode=stub"));
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
          .isGreaterThanOrEqualTo(140);
      assertThat(requests.getLast().body().path("messages").get(1).path("content").asText())
          .contains("当前没有可用的内部资料");
    }
    assertThat(logs.toString())
        .doesNotContain(
            "private-query-42", "model-secret", "classifier-secret", "https://image.test");
  }

  @Test
  void classifierFailureRecordsFailedStageAndTotalWithoutModelCall() {
    handler = r -> new Reply(503, "application/json", Flux.just("private-upstream-secret"));
    var input = input();
    input.put("image_url", List.of("https://image.test/a.jpg"));
    assertThat(result(run(input, stub())).path("code").asText()).isEqualTo("500");
    awaitTotals(1);
    assertThat(logs.stream().map(l -> field(l, "stage") + ":" + field(l, "status")))
        .containsExactly("validate:succeeded", "classify:failed", "total:failed");
    assertThat(requests).hasSize(1);
    assertThat(logs.toString()).doesNotContain("private-upstream-secret");
  }

  @Test
  void cancellationDuringStubRecordsCancelledStageAndTotal() {
    StepVerifier.create(events(stub()).stream(payload(input())))
        .expectNextCount(3)
        .thenCancel()
        .verify(Duration.ofSeconds(3));
    awaitTotals(1);
    assertThat(logs.stream().map(l -> field(l, "stage") + ":" + field(l, "status")))
        .containsExactly("validate:succeeded", "search_text:cancelled", "total:cancelled");
    assertThat(requests).isEmpty();
  }

  @Test
  void concurrentRequestsHaveIndependentExecutionIdsAndOneTotalEach() {
    var workflow = events(stub());
    var completed =
        Flux.range(0, 4)
            .flatMap(i -> workflow.stream(payload(input())).collectList())
            .collectList()
            .block(Duration.ofSeconds(5));
    assertThat(completed).hasSize(4);
    awaitTotals(4);
    var grouped = logs.stream().collect(Collectors.groupingBy(l -> field(l, "execution_id")));
    assertThat(grouped).hasSize(4);
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
      assertThat(result(run(input(), stub())).path("code").asText()).isEqualTo("0");
      awaitTotals(1);
    } finally {
      logger.detachAppender(broken);
      broken.stop();
    }
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
