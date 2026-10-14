package com.heilos.travel;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;

/** Runtime configuration; never render this object in logs. */
public final class AgentSettings {
  final String classifierUrl, classifierKey;
  final String workflowId, workflowName;
  final int maxRequestBytes, maxImageBytes, maxContextChars;
  final double classifierThreshold;
  final boolean classifierFallbackOnTransientError;
  final Duration classifierTimeout, modelTimeout, heartbeat;
  final Set<String> modelAllowedHosts;
  final RagFlow ragflow;
  final boolean ragflowLogContent, modelLogRequest;
  final int diagnosticLogMaxChars;

  record RagFlow(
      boolean enabled,
      String url,
      String token,
      String tokenFile,
      String datasetId,
      String rerankId,
      boolean useRerank,
      Integer knnTopK,
      Integer knnNumCandidates,
      Integer rerankCandidatesCount,
      int topK,
      double threshold,
      double minSimilarity,
      Duration timeout,
      int retries) {
    boolean rerankEnabled() {
      return useRerank && !rerankId.isEmpty();
    }

    @Override
    public String toString() {
      return "RagFlow[redacted]";
    }
  }

  public AgentSettings(Environment env) {
    ragflow =
        new RagFlow(
            bool(env, "RAGFLOW_ENABLED", true),
            url(env, "RAGFLOW_URL", ""),
            value(env, "RAGFLOW_API_TOKEN", ""),
            value(env, "RAGFLOW_API_TOKEN_FILE", "").strip(),
            value(env, "RAGFLOW_DATASET_ID", "").strip(),
            value(env, "RAGFLOW_RERANK_ID", "").strip(),
            bool(env, "RAGFLOW_USE_RERANK", true),
            optionalPositiveInteger(env, "RAGFLOW_KNN_TOP_K", 64),
            optionalPositiveInteger(env, "RAGFLOW_KNN_NUM_CANDIDATES", 128),
            optionalPositiveInteger(env, "RAGFLOW_RERANK_CANDIDATES_COUNT", 32),
            integer(env, "RAGFLOW_TOP_K", 3, 50),
            number(env, "RAGFLOW_SIMILARITY_THRESHOLD", .2, 0, 1),
            number(env, "RAGFLOW_MIN_SIMILARITY", .3, 0, 1),
            duration(env, "RAGFLOW_TIMEOUT", 10, 300),
            integer(env, "RAGFLOW_RETRIES", 2, 0, 2));
    ragflowLogContent = bool(env, "RAGFLOW_LOG_CONTENT", false);
    modelLogRequest = bool(env, "MODEL_LOG_REQUEST", false);
    diagnosticLogMaxChars = integer(env, "DIAGNOSTIC_LOG_MAX_CHARS", 16000, 256, 65536);
    classifierUrl = url(env, "CLASSIFIER_URL", "http://1.95.152.66:16000/mobilenetv3large");
    classifierKey = value(env, "CLASSIFIER_API_KEY", "");
    maxRequestBytes = integer(env, "MAX_REQUEST_BYTES", 16 * 1024 * 1024, Integer.MAX_VALUE);
    maxImageBytes = integer(env, "MAX_IMAGE_BYTES", 10 * 1024 * 1024, Integer.MAX_VALUE);
    maxContextChars = integer(env, "MAX_CONTEXT_CHARS", 16000, Integer.MAX_VALUE);
    classifierThreshold = number(env, "CLASSIFIER_THRESHOLD", .7, 0, 1);
    classifierFallbackOnTransientError = bool(env, "CLASSIFIER_FALLBACK_ON_TRANSIENT_ERROR", false);
    classifierTimeout = duration(env, "CLASSIFIER_TIMEOUT", 30, 300);
    modelTimeout = duration(env, "MODEL_TIMEOUT", 60, 600);
    heartbeat = duration(env, "HEARTBEAT_SECONDS", 15, 60);
    modelAllowedHosts =
        Arrays.stream(value(env, "MODEL_ALLOWED_HOSTS", "").split(","))
            .map(String::strip)
            .map(s -> s.toLowerCase(Locale.ROOT))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    workflowId = value(env, "WORKFLOW_ID", "wenlv-highcode");
    workflowName = value(env, "WORKFLOW_NAME", "文旅逛展");
  }

  static boolean validId(String value) {
    return value.matches("[A-Za-z0-9_-]{1,64}");
  }

  static URI httpUri(String value) {
    try {
      URI uri = URI.create(value);
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawFragment() != null
          || uri.getPort() == 0
          || uri.getPort() > 65535
          || value
              .codePoints()
              .anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
        throw new IllegalArgumentException();
      return uri;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("必须是有效的HTTP(S)地址，不能包含凭证或fragment");
    }
  }

  private static String value(Environment env, String name, String fallback) {
    String value = env.getProperty(name);
    return value == null || value.isEmpty() ? fallback : value;
  }

  private static String url(Environment env, String name, String fallback) {
    String value = value(env, name, fallback).strip();
    if (!value.isEmpty()) httpUri(value);
    return value;
  }

  private static boolean bool(Environment env, String name, boolean fallback) {
    return switch (value(env, name, Boolean.toString(fallback)).strip().toLowerCase(Locale.ROOT)) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new IllegalArgumentException("配置必须为true或false：" + name);
    };
  }

  private static double number(
      Environment env, String name, double fallback, double min, double max) {
    try {
      double value = Double.parseDouble(value(env, name, Double.toString(fallback)));
      if (!Double.isFinite(value) || value < min || value > max)
        throw new IllegalArgumentException();
      return value;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("配置范围错误：" + name);
    }
  }

  private static int integer(Environment env, String name, int fallback, int max) {
    return integer(env, name, fallback, 1, max);
  }

  private static int integer(Environment env, String name, int fallback, int min, int max) {
    double value = number(env, name, fallback, min, max);
    if (value != Math.floor(value)) throw new IllegalArgumentException("配置必须为整数：" + name);
    return (int) value;
  }

  private static Duration duration(Environment env, String name, double fallback, double max) {
    double seconds = number(env, name, fallback, .000001, max);
    return Duration.ofNanos((long) (seconds * 1_000_000_000));
  }

  private static Integer optionalPositiveInteger(Environment env, String name, int fallback) {
    String raw = env.getProperty(name);
    if (raw == null) return fallback;
    raw = raw.strip();
    // Explicit blank/null omits the field; an absent variable uses our recommended default.
    if (raw.isEmpty() || raw.equalsIgnoreCase("null")) return null;
    try {
      int value = Integer.parseInt(raw);
      if (value > 0) return value;
    } catch (NumberFormatException ignored) {
      // Do not include the supplied value in configuration errors.
    }
    throw new IllegalArgumentException("配置必须为正整数、空值或null：" + name);
  }
}
