package com.heilos.travel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Per-subscription timing only; never receives request data or credentials. */
final class WorkflowTiming {
  private static final Logger LOG = LoggerFactory.getLogger(WorkflowTiming.class);
  private final String executionId;
  private final String searchMode;
  private final long started = System.nanoTime();
  private String stage;
  private long stageStarted;
  private boolean finished;

  WorkflowTiming(String executionId, String searchMode) {
    this.executionId = executionId;
    this.searchMode = searchMode;
  }

  synchronized void startStage(String next) {
    if (finished) return;
    finishStage("succeeded");
    stage = next;
    stageStarted = System.nanoTime();
  }

  synchronized void finishStage(String status) {
    if (stage == null) return;
    String current = stage;
    stage = null;
    log(current, status, stageStarted);
  }

  synchronized void finish(String status) {
    if (finished) return;
    finished = true;
    finishStage(status);
    log("total", status, started);
  }

  private void log(String stage, String status, long since) {
    double elapsedMs = Math.round((System.nanoTime() - since) / 1000.0) / 1000.0;
    try {
      LOG.info(
          "workflow_timing execution_id={} stage={} status={} elapsed_ms={} search_mode={}",
          executionId,
          stage,
          status,
          elapsedMs,
          searchMode);
    } catch (RuntimeException ignored) {
      // Instrumentation failures must not change SSE results or upstream cancellation.
    }
  }
}
