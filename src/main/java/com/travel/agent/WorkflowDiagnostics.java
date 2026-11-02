package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/** Bounded, opt-in payload diagnostics. Log failures must never affect the workflow. */
final class WorkflowDiagnostics {
  static final String EXECUTION_ID = WorkflowDiagnostics.class.getName() + ".executionId";
  static final String SECRETS = WorkflowDiagnostics.class.getName() + ".secrets";
  private static final Logger LOG = LoggerFactory.getLogger(WorkflowDiagnostics.class);
  private static final Set<String> SECRET_KEYS = Set.of(
      "authorization", "proxyauthorization", "authtoken", "temptoken", "apitoken", "apikey",
      "xapikey", "xauthtoken", "xapigappcode", "accesskey", "accesskeyid", "secretkey",
      "secretaccesskey", "password", "token", "credentials");
  private final AgentSettings settings;

  WorkflowDiagnostics(AgentSettings settings) {
    this.settings = settings;
  }

  void runtimeConfig() {
    emit(Context.empty(), "ragflow_config", () -> {
      var fields = configuration();
      fields.put("content_logging", settings.isRagflowContentLoggingEnabled);
      fields.put("model_request_logging", settings.isModelRequestLoggingEnabled);
      fields.put("max_chars", settings.diagnosticLogMaxChars);
      return fields;
    });
  }

  void retrievalResult(ContextView context, String query, JsonNode rawChunks,
      List<ExternalServices.KnowledgeChunk> chunks, int attempts, String token) {
    // The SECRETS set is a ConcurrentHashMap.newKeySet() put into context by WorkflowEvents
    // (context itself is immutable; this mutable set is the intended accumulator). Tokens read
    // from a rotating file are added here so later emits (e.g. model_request) redact them too.
    // Mutating accumulator pattern; context itself is immutable.
    try {
      context.<Set<String>>getOrEmpty(SECRETS).ifPresent(secrets -> secrets.add(token));
    } catch (RuntimeException ignored) {
      return;
    }
    emit(context, "ragflow_result", () -> {
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("status", "succeeded");
      fields.put("attempts", attempts);
      fields.put("returned_chunks", rawChunks.size());
      fields.put("kept_chunks", chunks.size());
      fields.put("empty_result", chunks.isEmpty());
      fields.put("selected", chunks.stream().map(chunk -> Map.of(
          "id", chunk.id(), "document", chunk.title(), "similarity", chunk.similarity(),
          "content_chars", Json.length(chunk.content()))).toList());
      return fields;
    }, token);
    if (settings.isRagflowContentLoggingEnabled) {
      emit(context, "ragflow_content", () -> Map.of("question", query, "chunks", rawChunks), token);
    }
  }

  void retrievalSkipped(ContextView context) {
    emit(context, "ragflow_skipped", () -> Map.of(
        "status", "skipped", "reason", "RAGFLOW_ENABLED=false", "attempts", 0,
        "kept_chunks", 0));
  }

  void retrievalFailure(ContextView context, int attempts, Throwable error) {
    emit(context, "ragflow_failed", () -> {
      var fields = configuration();
      fields.put("status", "failed");
      fields.put("attempts", attempts);
      // Called after RagFlowClient maps upstream exceptions to controlled application errors.
      fields.put("code", error instanceof AgentException known ? known.code() : "E5002");
      fields.put("reason", error instanceof AgentException ? error.getMessage() : "检索调用异常");
      return fields;
    });
  }

  void classifierFailure(ContextView context, String code, String kind, String reason,
      boolean fallback) {
    emit(context, "classifier_failed", () -> Map.of(
        "status", "service_failed", "code", code, "failure_kind", kind,
        "reason", reason, "fallback_to_vision", fallback, "alert", true));
  }

  void modelRequest(ContextView context, Inputs.ModelConfig config, Object body) {
    emit(context, "model_request", () -> {
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("model", config.model());
      fields.put("type", config.type());
      fields.put("provider", config.provider());
      fields.put("stream", true);
      fields.put("body_logged", settings.isModelRequestLoggingEnabled);
      if (settings.isModelRequestLoggingEnabled) {
        fields.put("body", body);
      }
      return fields;
    }, config.token());
  }

  void modelFailure(ContextView context, Inputs.ModelConfig config,
      ModelFailureDetails details, Throwable error) {
    emit(context, "model_failed", () -> {
      var fields = details.fields(error);
      fields.put("model", config.model());
      fields.put("type", config.type());
      fields.put("provider", config.provider());
      fields.put("code", error instanceof AgentException known ? known.code() : "E5003");
      return fields;
    }, config.token());
  }

  private Map<String, Object> configuration() {
    var ragflow = settings.ragflow;
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("enabled", ragflow.enabled());
    fields.put("url_configured", !ragflow.url().isEmpty());
    fields.put("dataset_id_configured", !ragflow.datasetId().isEmpty());
    fields.put("dataset_id_valid", AgentSettings.validId(ragflow.datasetId()));
    fields.put("token_source", !ragflow.tokenFile().isEmpty() ? "file"
        : ragflow.token().isBlank() ? "missing" : "environment");
    fields.put("use_rerank", ragflow.useRerank());
    fields.put("rerank_enabled", ragflow.isRerankEnabled());
    fields.put("knn_top_k", ragflow.knnTopK());
    fields.put("knn_num_candidates", ragflow.knnNumCandidates());
    fields.put("rerank_candidates_count",
        ragflow.isRerankEnabled() ? ragflow.rerankCandidatesCount() : null);
    return fields;
  }

  private void emit(ContextView context, String event, Supplier<Map<String, Object>> data,
      String... extraSecrets) {
    try {
      if (!LOG.isInfoEnabled()) {
        return;
      }
      List<String> secrets = new ArrayList<>();
      for (String value : List.of(settings.classifierKey, settings.ragflow.token())) {
        addSecret(secrets, value);
      }
      for (String value : extraSecrets) {
        addSecret(secrets, value);
      }
      context.<Set<String>>getOrEmpty(SECRETS)
          .ifPresent(values -> values.forEach(value -> addSecret(secrets, value)));
      String json = Json.write(redact(Json.MAPPER.valueToTree(data.get()), secrets));
      int chars = Json.length(json);
      boolean truncated = chars > settings.diagnosticLogMaxChars;
      // Keep each log line valid JSON even when the body preview is truncated.
      if (truncated) {
        json = Json.write(Map.of(
            "original_chars", chars, "preview", Json.prefix(json, settings.diagnosticLogMaxChars)));
      }
      LOG.info("workflow_diagnostic execution_id={} event={} truncated={} data={}",
          context.getOrDefault(EXECUTION_ID, "none"), event, truncated, json);
    } catch (RuntimeException ignored) { // Serialization, redaction and appender failures must not change requests or SSE results.
    }
  }

  private static void addSecret(List<String> secrets, String value) {
    value = value.strip();
    if (!value.isEmpty()) {
      secrets.add(value);
      if (value.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
        secrets.add(value.substring(7).strip());
      }
    }
  }

  private static JsonNode redact(JsonNode node, List<String> secrets) {
    if (node.isObject()) {
      ObjectNode result = Json.MAPPER.createObjectNode();
      node.properties().forEach(field -> {
        String key = field.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (SECRET_KEYS.contains(key)) {
          result.put(field.getKey(), "<REDACTED>");
        } else if (Set.of("imageurl", "imagebase64").contains(key)) {
          result.put(field.getKey(), "<IMAGE_OMITTED>");
        } else {
          result.set(field.getKey(), redact(field.getValue(), secrets));
        }
      });
      return result;
    }
    if (node.isArray()) {
      ArrayNode result = Json.MAPPER.createArrayNode();
      node.forEach(value -> result.add(redact(value, secrets)));
      return result;
    }
    if (node.isTextual()) {
      String value = node.asText();
      if (value.startsWith("data:image/")) {
        return TextNode.valueOf("<IMAGE_OMITTED>");
      }
      for (String secret : secrets) {
        value = value.replace(secret, "<REDACTED>");
      }
      return TextNode.valueOf(value);
    }
    return node;
  }
}
