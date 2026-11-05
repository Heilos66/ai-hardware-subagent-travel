package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.SynchronousSink;

final class WorkflowEvents {
  private static final Logger LOG = LoggerFactory.getLogger(WorkflowEvents.class);
  private final TravelAgent agent;
  private final AgentSettings settings;

  WorkflowEvents(TravelAgent agent, AgentSettings settings) {
    this.agent = agent;
    this.settings = settings;
  }

  Flux<String> stream(JsonNode payload) {
    return Flux.defer(
        () -> {
          State state = new State(settings);
          return withHeartbeat(
                  Flux.concat(
                      Flux.just(event("workflow_started", Map.of("start_time", state.start))),
                      workflowBody(payload, state),
                      Flux.defer(() -> finish(state))),
                  settings.heartbeat)
              .doFinally(
                  signal ->
                      state.timing.finish(
                          signal == SignalType.CANCEL
                              ? "cancelled"
                              : state.failed || signal == SignalType.ON_ERROR
                                  ? "failed"
                                  : "succeeded"))
              .contextWrite(context -> context
                  .put(WorkflowTiming.class, state.timing)
                  .put(WorkflowDiagnostics.EXECUTION_ID, state.executionId)
                  .put(WorkflowDiagnostics.SECRETS, ConcurrentHashMap.<String>newKeySet()));
        });
  }

  private Flux<String> workflowBody(JsonNode payload, State state) {
    return agent.run(payload)
        .<String>handle((step, sink) -> writeStep(step, state, sink))
        .onErrorResume(error -> fail(state, error));
  }

  private void writeStep(TravelAgent.Step step, State state, SynchronousSink<String> sink) {
    if (step instanceof TravelAgent.Result result) {
      state.result = result.content();
      state.timing.finishStage("succeeded");
      return;
    }
    state.index++;
    if (step instanceof TravelAgent.Delta delta) {
      sink.next(message(delta.text(), delta.reasoning(), state.index));
    } else if (step instanceof TravelAgent.Stage stage) {
      state.timing.startStage(stage.id());
      sink.next(nodeStarted(stage, state));
    }
  }

  private String nodeStarted(TravelAgent.Stage stage, State state) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("agent_id", settings.workflowId);
    data.put("node_id", stage.id());
    data.put("node_status", "node_started");
    data.put("parent_workflow_id", "");
    data.put("status", Map.of("code", 0, "desc", "succeeded"));
    data.put("node_name", stage.name());
    data.put("node_type", stage.type());
    data.put("inputs", Map.of());
    data.put("outputs", Map.of());
    data.put("start_time", System.currentTimeMillis());
    data.put("execution_id", state.executionId);
    return event("node_started", data);
  }

  private Flux<String> fail(State state, Throwable error) {
    state.failed = true;
    state.timing.finishStage("failed");
    if (error instanceof AgentException known) {
      state.result = known.result();
    } else {
      // Never log exception text, URLs, prompts, or stack traces with credentials.
      LOG.error(
          "workflow_failed execution_id={} type={}",
          state.executionId,
          error.getClass().getSimpleName());
      state.result = new AgentException("E5004", "内部处理异常").result();
    }
    return Flux.empty();
  }

  private Flux<String> finish(State state) {
    if (state.result == null) {
      state.failed = true;
      state.result = new AgentException("E5004", "工作流未生成结果").result();
    }
    String serialized = Json.write(state.result);
    Flux<String> capabilities =
        state.result.containsKey("capabilities")
            ? Flux.just(message(serialized, "", ++state.index))
            : Flux.empty();
    return Flux.concat(
        capabilities,
        Flux.just(
            event(
                "workflow_finished",
                Map.of(
                    "status",
                    Map.of(
                        "code",
                        state.failed ? -1 : 1,
                        "desc",
                        state.failed ? "failed" : "succeeded"),
                    "outputs",
                    Map.of("responseContent", serialized),
                    "metadata",
                    Map.of("enable_history", false),
                    "start_time",
                    state.start,
                    "end_time",
                    System.currentTimeMillis(),
                    "execution_id",
                    state.executionId)),
            event("end", null)));
  }

  private String message(String text, String reasoning, int index) {
    return event(
        "message",
        Map.of(
            "text",
            text,
            "reasoning_content",
            reasoning,
            "index",
            index,
            "node_id",
            "node_end",
            "node_type",
            "End",
            "node_name",
            "结束",
            "workflow_id",
            settings.workflowId,
            "workflow_name",
            settings.workflowName,
            "createdTime",
            System.currentTimeMillis()));
  }

  static String event(String name, Map<String, Object> data) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("event", name);
    body.put("createdTime", System.currentTimeMillis());
    if (data != null) {
      body.put("data", data);
    }
    return "event: " + name + "\ndata: " + Json.write(body) + "\n\n";
  }

  static Flux<String> withHeartbeat(Flux<String> source, Duration interval) {
    return Flux.defer(
        () -> {
          AtomicLong last = new AtomicLong(System.nanoTime());
          return source
              .doOnNext(frame -> last.set(System.nanoTime()))
              .publish(
                  shared ->
                      Flux.merge(
                          shared,
                          Flux.interval(interval)
                              .filter(tick -> System.nanoTime() - last.get() >= interval.toNanos())
                              .map(tick -> ": keep-alive\n\n")
                              .takeUntilOther(shared.then())));
        });
  }

  private static final class State {
    final String executionId = UUID.randomUUID().toString();
    final long start = System.currentTimeMillis();
    final WorkflowTiming timing;
    int index;
    boolean failed;
    Map<String, Object> result;

    State(AgentSettings settings) {
      timing = new WorkflowTiming(executionId, settings.ragflow.enabled(), settings.knowledgeBackend);
    }
  }
}
