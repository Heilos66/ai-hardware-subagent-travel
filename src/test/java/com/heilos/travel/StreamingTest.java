package com.heilos.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class StreamingTest extends AgentTestSupport {
  @Test
  void utf8OneByteChunksCrLfMultilineAndMissingFinalBlank() {
    String raw =
        "\ufeff: keep-alive\r\n\r\nevent: message\r\nid: 1\r\n"
            + "data: {\"choices\":\r\ndata: [{\"index\":0,\"delta\":{\"content\":\"中文\"}}]}\r\n\r\n"
            + delta(" and 日本語\n", "")
            + "data: [DONE]";
    byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
    var buffers =
        Flux.fromStream(
            IntStream.range(0, bytes.length)
                .mapToObj(
                    i -> DefaultDataBufferFactory.sharedInstance.wrap(new byte[] {bytes[i]})));
    var events = SseDecoder.decode(buffers).collectList().block();
    assertThat(events).hasSize(3);
    assertThat(
            Json.parse(events.getFirst().data())
                .path("choices")
                .get(0)
                .path("delta")
                .path("content")
                .asText())
        .isEqualTo("中文");
    assertThat(events.getLast().data()).isEqualTo("[DONE]");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "event: error\ndata: private-secret\n\n",
        "data: {\"error\":\"private-secret\"}\n\n",
        "data: private-secret\n\n",
        "data: {\"choices\":[{\"delta\":{\"content\":123}}]}\n\n"
      })
  void partialAnswerNeverBecomesSuccess(String tail) {
    handler = r -> r.uri().startsWith("/api/v1/retrieval") ? success(r) : Reply.sse(delta("部分回答", "") + tail);
    var events = run(input());
    assertThat(result(events).path("code").asText()).isEqualTo("E5003");
    assertThat(messages(events).stream().map(m -> m.path("text").asText()).toList())
        .containsExactly("部分回答");
    assertThat(events.getLast().path("event").asText()).isEqualTo("end");
    assertThat(events.get(events.size() - 2).path("data").path("status").path("desc").asText())
        .isEqualTo("failed");
    assertThat(events.toString()).doesNotContain("private-secret");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "data: [DONE]\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"
      })
  void bothCompletionMarkers(String tail) {
    handler =
        r -> r.uri().startsWith("/api/v1/retrieval") ? success(r) : Reply.sse(delta(" 保留空格 ", "") + tail);
    var result = result(run(input()));
    assertThat(result.path("code").asText()).isEqualTo("0");
    assertThat(result.path("message").asText()).isEqualTo(" 保留空格 ");
  }

  @ParameterizedTest
  @ValueSource(strings = {"false", "1", "null"})
  void invalidFinishReasonCannotMaskTruncation(String reason) {
    handler =
        r ->
            r.uri().startsWith("/api/v1/retrieval")
                ? success(r)
                : Reply.sse(
                    delta("部分回答", "")
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":"
                        + reason
                        + "}]}\n\n");
    assertThat(result(run(input())).path("code").asText()).isEqualTo("E5003");
  }

  @ParameterizedTest
  @ValueSource(strings = {"length", "tool_calls", "function_call", "unknown"})
  void failedFinishCannotBeOverriddenByDoneOrStop(String reason) {
    String terminal = "data: " + Json.write(Map.of("choices", List.of(Map.of(
        "delta", Map.of(), "finish_reason", reason)))) + "\n\n";
    String stop = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";
    handler = r -> r.uri().startsWith("/api/v1/retrieval") ? success(r)
        : Reply.sse(terminal + stop + DONE);
    var events = run(input());
    assertThat(result(events).path("code").asText()).isEqualTo("E5003");
    assertThat(messages(events)).isEmpty();
    assertThat(events.getLast().path("event").asText()).isEqualTo("end");
  }

  @Test
  void reasoningOnlyFailsButIsDeliveredSeparately() {
    handler = r -> r.uri().startsWith("/api/v1/retrieval") ? success(r) : Reply.sse(delta("", "分析片段") + DONE);
    var events = run(input());
    assertThat(result(events).path("code").asText()).isEqualTo("E5003");
    assertThat(messages(events)).hasSize(1);
    assertThat(messages(events).getFirst().path("text").asText()).isEmpty();
    assertThat(messages(events).getFirst().path("reasoning_content").asText()).isEqualTo("分析片段");
  }

  @Test
  void jsonFallbackPreservesTextAndReasoning() {
    handler =
        r ->
            r.uri().startsWith("/api/v1/retrieval")
                ? success(r)
                : Reply.json(
                    Map.of(
                        "choices",
                        List.of(
                            Map.of(
                                "message",
                                Map.of(
                                    "content",
                                    "  first\nsecond\n",
                                    "reasoning_content",
                                    "分析片段")))));
    var events = run(input());
    assertThat(result(events).path("message").asText()).isEqualTo("  first\nsecond\n");
    assertThat(messages(events)).hasSize(1);
    assertThat(messages(events).getFirst().path("reasoning_content").asText()).isEqualTo("分析片段");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "\"0\""})
  void legacyBusinessJsonFallback(String code) {
    handler =
        r ->
            r.uri().startsWith("/api/v1/retrieval")
                ? success(r)
                : new Reply(
                    200,
                    "application/json",
                    Flux.just("{\"code\":" + code + ",\"message\":\"ok\"}"));
    assertThat(result(run(input())).path("message").asText()).isEqualTo("ok");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"choices\":[]}",
        "{\"code\":false,\"message\":\"secret\"}",
        "{\"choices\":[{\"message\":{\"content\":[]}}]}",
        "<html>secret</html>"
      })
  void invalidFallbackIsSafe(String raw) {
    handler =
        r ->
            r.uri().startsWith("/api/v1/retrieval")
                ? success(r)
                : new Reply(200, "application/json", Flux.just(raw));
    var events = run(input());
    assertThat(result(events).path("code").asText()).isEqualTo("E5003");
    assertThat(events.toString()).doesNotContain("secret");
  }

  @Test
  void firstFrameArrivesBeforeUpstreamCompletes() {
    Sinks.One<Void> release = Sinks.one();
    handler =
        r ->
            r.uri().startsWith("/api/v1/retrieval")
                ? success(r)
                : new Reply(
                    200,
                    "text/event-stream",
                    Flux.concat(
                        Flux.just(delta("第一段", "")),
                        release.asMono().thenMany(Flux.just(delta("第二段", "") + DONE))));
    AtomicBoolean received = new AtomicBoolean();
    var frames =
        events(settings()).stream(payload(input()))
            .doOnNext(
                frame -> {
                  if (frame.startsWith("event: message") && frame.contains("第一段")) {
                    received.set(true);
                    release.tryEmitEmpty();
                  }
                })
            .collectList()
            .block(Duration.ofSeconds(3));
    assertThat(received).isTrue();
    assertThat(result(frames(frames)).path("message").asText()).isEqualTo("第一段第二段");
  }

  @Test
  void totalDeadlineDoesNotResetOnEachDeltaAndClosesConnection() {
    AtomicBoolean cancelled = new AtomicBoolean();
    handler =
        r ->
            r.uri().startsWith("/api/v1/retrieval")
                ? success(r)
                : new Reply(
                    200,
                    "text/event-stream",
                    Flux.interval(Duration.ofMillis(15))
                        .map(i -> delta("片段", ""))
                        .doFinally(signal -> cancelled.set(signal == SignalType.CANCEL)));
    long start = System.nanoTime();
    var events =
        run(input(), settings(Map.of("MODEL_TIMEOUT", "0.25", "HEARTBEAT_SECONDS", "0.02")));
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    assertThat(messages(events)).isNotEmpty();
    assertThat(result(events).path("code").asText()).isEqualTo("E5003");
    assertThat(result(events).path("message").asText()).contains("超时");
    await().atMost(Duration.ofSeconds(2)).untilTrue(cancelled);
  }

  @Test
  void heartbeatKeepsSlowCallAliveAndCancellationPropagates() {
    AtomicBoolean cancelled = new AtomicBoolean();
    handler =
        r ->
            r.uri().startsWith("/api/v1/retrieval")
                ? success(r)
                : new Reply(
                    200,
                    "text/event-stream",
                    Flux.concat(Flux.just(delta("第一段", "")), Flux.<String>never())
                        .doFinally(signal -> cancelled.set(signal == SignalType.CANCEL)));
    var settings = settings(Map.of("HEARTBEAT_SECONDS", "0.02"));
    AtomicBoolean first = new AtomicBoolean();
    StepVerifier.create(
            events(settings).stream(payload(input()))
                .doOnNext(
                    frame -> {
                      if (frame.contains("第一段")) first.set(true);
                    })
                .filter(frame -> first.get() && frame.startsWith(":")))
        .expectNext(": keep-alive\n\n")
        .thenCancel()
        .verify(Duration.ofSeconds(3));
    await().atMost(Duration.ofSeconds(2)).untilTrue(cancelled);
    assertThat(requests).hasSize(2); // Heartbeat never resubscribes and duplicates paid calls.
  }

  @Test
  void cancellingModelAfterFirstChunkClosesUpstream() {
    AtomicBoolean cancelled = new AtomicBoolean();
    handler =
        r ->
            new Reply(
                200,
                "text/event-stream",
                Flux.concat(Flux.just(delta("第一段", "")), Flux.<String>never())
                    .doFinally(signal -> cancelled.set(signal == SignalType.CANCEL)));
    var model = new CompatibleChatModel(http, settings(), config(model("text")));
    StepVerifier.create(model.stream(new Prompt("test")))
        .expectNextMatches(r -> r.getResult().getOutput().getText().equals("第一段"))
        .thenCancel()
        .verify(Duration.ofSeconds(3));
    await().atMost(Duration.ofSeconds(2)).untilTrue(cancelled);
  }

  @Test
  void eventLimitBoundsUnterminatedLines() {
    byte[] bytes =
        ("data: " + "x".repeat(SseDecoder.MAX_EVENT_BYTES)).getBytes(StandardCharsets.UTF_8);
    StepVerifier.create(
            SseDecoder.decode(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))))
        .expectErrorMatches(e -> e instanceof AgentException error && error.code().equals("E5003"))
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void roleUsageFramesIgnoredAndContentFilterFails() {
    handler =
        r ->
            r.uri().startsWith("/api/v1/retrieval")
                ? success(r)
                : Reply.sse(
                    "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n"
                        + delta("text", "")
                        + "data: {\"choices\":[],\"usage\":{}}\n\n"
                        + DONE);
    assertThat(result(run(input())).path("code").asText()).isEqualTo("0");
    handler =
        r ->
            r.uri().startsWith("/api/v1/retrieval")
                ? success(r)
                : Reply.sse(
                    delta("部分", "")
                        + "data:"
                        + " {\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\n");
    assertThat(result(run(input())).path("code").asText()).isEqualTo("E5003");
  }
}
