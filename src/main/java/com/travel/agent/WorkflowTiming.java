package com.travel.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Per-subscription timing only; never receives request data or credentials. */
final class WorkflowTiming {
  private static final Logger LOG = LoggerFactory.getLogger(WorkflowTiming.class);
  private static final Pattern EXECUTION_ID_PATTERN = Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private final String logExecutionId;
  private final String searchMode;
  private final long started = System.nanoTime();
  private final Map<String, Long> completedNanos = new HashMap<>();
  private String stage;
  private long stageStarted;
  private boolean finished;
  private boolean firstTokenReceived;

  WorkflowTiming(String executionId, boolean retrievalEnabled) {
    this.logExecutionId = maskId(executionId);
    this.searchMode = retrievalEnabled ? "ragflow" : "disabled";
  }

  synchronized void startStage(String next) {
    if (finished) {
      return;
    }
    long now = System.nanoTime();
    finishStage("succeeded", now);
    stage = stageName(next);
    stageStarted = now;
  }

  synchronized void finishStage(String status) {
    finishStage(status, System.nanoTime());
  }

  private void finishStage(String status, long now) {
    if (stage == null) {
      return;
    }
    String current = stage;
    stage = null;
    long elapsed = now - stageStarted;
    completedNanos.merge(current, elapsed, Long::sum);
    log(current, status, elapsed);
  }

  synchronized void firstToken(boolean hasContent, boolean hasReasoning, boolean streaming) {
    if (finished || firstTokenReceived || !"model".equals(stage)
        || (!hasContent && !hasReasoning)) {
      return;
    }
    long now = System.nanoTime();
    firstTokenReceived = true;
    long validate = completedNanos.getOrDefault("validate", 0L);
    long classify = completedNanos.getOrDefault("classify", 0L);
    long searchText = completedNanos.getOrDefault("search_text", 0L);
    long searchImage = completedNanos.getOrDefault("search_image", 0L);
    long modelFirst = now - stageStarted;
    long sum = validate + classify + searchText + searchImage + modelFirst;
    long ttft = now - started;
    String type = hasContent && hasReasoning ? "content_and_reasoning"
        : hasReasoning ? "reasoning_content" : "content";
    try {
      // G.OTH.06: only a masked UUID, durations and fixed categories are logged.
      // "token" denotes a generated response fragment, never an authentication credential.
      LOG.info(
          "workflow_ttft execution_id={} ttft_ms={} validate_ms={} classify_ms={}"
              + " search_text_ms={} search_image_ms={} model_to_first_token_ms={}"
              + " stages_sum_ms={} overhead_ms={} first_token_type={} response_mode={}"
              + " search_mode={}",
          logExecutionId, millis(ttft), millis(validate), millis(classify), millis(searchText),
          millis(searchImage), millis(modelFirst), millis(sum), millis(ttft - sum), type,
          streaming ? "sse" : "json", searchMode);
    } catch (RuntimeException ignored) { // A failed TTFT log must not consume a token or interrupt the model stream.
    }
  }

  synchronized void finish(String status) {
    if (finished) {
      return;
    }
    long now = System.nanoTime();
    finished = true;
    finishStage(status, now);
    log("total", status, now - started);
  }

  private static double millis(long nanos) {
    return Math.round(nanos / 1000.0) / 1000.0;
  }

  private static String maskId(String id) {
    if (id == null || !EXECUTION_ID_PATTERN.matcher(id).matches()) {
      return "******";
    }
    // Internally generated UUIDs have a 28-character suffix; literal replacement
    // cannot match the retained 8-character prefix or interpret regex metacharacters.
    return id.replace(id.substring(8), "******");
  }

  private static String stageName(String value) {
    return switch (value) {
      case "validate" -> "validate";
      case "classify" -> "classify";
      case "search_text" -> "search_text";
      case "search_image" -> "search_image";
      case "model" -> "model";
      case null, default -> "unknown";
    };
  }

  private static String statusName(String value) {
    return switch (value) {
      case "succeeded" -> "succeeded";
      case "failed" -> "failed";
      case "cancelled" -> "cancelled";
      case null, default -> "unknown";
    };
  }

  private void log(String stage, String status, long elapsedNanos) {
    try {
      LOG.info(
          "workflow_timing execution_id={} stage={} status={} elapsed_ms={} search_mode={}",
          logExecutionId,
          stage,
          statusName(status),
          millis(elapsedNanos),
          searchMode);
    } catch (RuntimeException ignored) { // Instrumentation failures must not change SSE results or upstream cancellation.
    }
  }
}
