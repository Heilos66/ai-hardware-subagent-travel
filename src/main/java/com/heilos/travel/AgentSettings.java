package com.heilos.travel;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;

/** Uses the original environment variable names; never render this object in logs. */
public final class AgentSettings {
  final String searchMode, searchUrl, searchEndpoint, projectId, applicationId, textRepo, imageRepo;
  final String appCode, searchToken, tokenFile, classifierUrl, classifierKey;
  final String workflowId, workflowName;
  final int topK, maxRequestBytes, maxImageBytes, maxContextChars;
  final Double textThreshold, imageThreshold;
  final double classifierThreshold;
  final Duration classifierTimeout, searchTimeout, modelTimeout, heartbeat;
  final Set<String> modelAllowedHosts;

  public AgentSettings(Environment env) {
    searchMode = value(env, "KOOSEARCH_MODE", "stub").strip().toLowerCase(Locale.ROOT);
    if (!Set.of("stub", "live").contains(searchMode))
      throw new IllegalArgumentException("KOOSEARCH_MODE必须为stub或live");
    // Unfinished knowledge configuration must not block the temporary stub workflow.
    searchUrl = searchMode.equals("live") ? url(env, "KOOSEARCH_URL", "") : "";
    searchEndpoint = searchMode.equals("live") ? url(env, "KOOSEARCH_ENDPOINT", "") : "";
    projectId = value(env, "KOOSEARCH_PROJECT_ID", "");
    applicationId = value(env, "KOOSEARCH_APPLICATION_ID", "");
    textRepo = value(env, "KOOSEARCH_TEXT_REPO_ID", "");
    imageRepo = value(env, "KOOSEARCH_IMAGE_REPO_ID", "");
    appCode = value(env, "KOOSEARCH_APPCODE", "");
    searchToken = value(env, "KOOSEARCH_TOKEN", "");
    tokenFile = value(env, "KOOSEARCH_TOKEN_FILE", "");
    classifierUrl = url(env, "CLASSIFIER_URL", "http://1.95.152.66:16000/mobilenetv3large");
    classifierKey = value(env, "CLASSIFIER_API_KEY", "");
    topK = integer(env, "KOOSEARCH_TOP_K", 3, 50);
    maxRequestBytes = integer(env, "MAX_REQUEST_BYTES", 16 * 1024 * 1024, Integer.MAX_VALUE);
    maxImageBytes = integer(env, "MAX_IMAGE_BYTES", 10 * 1024 * 1024, Integer.MAX_VALUE);
    maxContextChars = integer(env, "MAX_CONTEXT_CHARS", 16000, Integer.MAX_VALUE);
    textThreshold = optionalNumber(env, "KOOSEARCH_TEXT_THRESHOLD");
    imageThreshold = optionalNumber(env, "KOOSEARCH_IMAGE_THRESHOLD");
    classifierThreshold = number(env, "CLASSIFIER_THRESHOLD", .7, 0, 1);
    classifierTimeout = duration(env, "CLASSIFIER_TIMEOUT", 30, 300);
    searchTimeout = duration(env, "SEARCH_TIMEOUT", 30, 300);
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

  String searchUrl() {
    if (!searchUrl.isEmpty()) return searchUrl;
    if (searchEndpoint.isEmpty() || !validId(projectId) || !validId(applicationId))
      throw new AgentException("E5004", "Koo Search 地址或项目/应用配置缺失");
    if (URI.create(searchEndpoint).getRawQuery() != null)
      throw new AgentException("E5004", "Koo Search 基础地址不能包含查询参数");
    return searchEndpoint.replaceAll("/+$", "")
        + "/v1/"
        + projectId
        + "/applications/"
        + applicationId
        + "/uni-search/experience/searchtext";
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
    double value = number(env, name, fallback, 1, max);
    if (value != Math.floor(value)) throw new IllegalArgumentException("配置必须为整数：" + name);
    return (int) value;
  }

  private static Double optionalNumber(Environment env, String name) {
    return value(env, name, "").isEmpty() ? null : number(env, name, 0, 0, 200);
  }

  private static Duration duration(Environment env, String name, double fallback, double max) {
    double seconds = number(env, name, fallback, .000001, max);
    return Duration.ofNanos((long) (seconds * 1_000_000_000));
  }
}
