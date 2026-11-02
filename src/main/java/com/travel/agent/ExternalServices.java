package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

final class ExternalServices {
  enum ClassificationStatus {
    RECOGNIZED, UNRECOGNIZED, SERVICE_FAILED
  }

  record Classification(String name, double confidence, ClassificationStatus status) {
    Classification(String name, double confidence) {
      this(name, confidence, ClassificationStatus.RECOGNIZED);
    }
  }

  private static final class ClassifierFailure extends RuntimeException {
    final String code;
    final String kind;
    final boolean transientFailure;

    ClassifierFailure(String code, String kind, boolean transientFailure, String message) {
      super(message);
      this.code = code;
      this.kind = kind;
      this.transientFailure = transientFailure;
    }

    ClassifierFailure(String code, String kind, boolean transientFailure, String message, Throwable cause) {
      super(message, cause);
      this.code = code;
      this.kind = kind;
      this.transientFailure = transientFailure;
    }
  }

  record KnowledgeChunk(String content, String title, String id, double similarity) {}

  private final WebClient http;
  private final AgentSettings settings;
  private final RagFlowClient ragflow;

  ExternalServices(WebClient http, AgentSettings settings) {
    this.http = http;
    this.settings = settings;
    this.ragflow = new RagFlowClient(http, settings);
  }

  Mono<Classification> classify(ImageInput image) {
    return Mono.deferContextual(context -> Mono.defer(() -> requestClassification(image))
        .onErrorResume(error -> handleClassifierFailure(context, error)));
  }

  private Mono<Classification> requestClassification(ImageInput image) {
    String key = settings.classifierKey.strip();
    if (settings.classifierUrl.isBlank()) {
      throw new ClassifierFailure("E5004", "configuration", false,
          "图片分类服务地址未配置：CLASSIFIER_URL");
    }
    if (key.isEmpty() || key.startsWith("E1-") || key.startsWith("${")
        || key.chars().anyMatch(Character::isISOControl)) {
      throw new ClassifierFailure("E5004", "configuration", false,
          "图片分类服务密钥未配置或仍为平台加密值");
    }
    Map<String, String> body = Map.of("image_url", image.url(), "image_base64", image.base64());
    return postClassifier(key, body)
        .map(ExternalServices::parseClassification)
        .map(this::applyClassifierThreshold);
  }

  private Classification applyClassifierThreshold(Classification result) {
    return new Classification(result.name(), result.confidence(),
        result.confidence() >= settings.classifierThreshold
            ? ClassificationStatus.RECOGNIZED : ClassificationStatus.UNRECOGNIZED);
  }

  private Mono<Classification> handleClassifierFailure(ContextView context, Throwable error) {
    ClassifierFailure failure = classifyFailure(error);
    boolean fallback =
        failure.transientFailure && settings.isClassifierFallbackOnTransientErrorEnabled;
    new WorkflowDiagnostics(settings).classifierFailure(context, failure.code, failure.kind,
        failure.getMessage(), fallback);
    return fallback
        ? Mono.just(new Classification("", 0, ClassificationStatus.SERVICE_FAILED))
        : Mono.error(new AgentException(failure.code, failure.getMessage()));
  }

  private static ClassifierFailure classifyFailure(Throwable error) {
    if (error instanceof ClassifierFailure known) {
      return known;
    }
    if (error instanceof TimeoutException) {
      return new ClassifierFailure("500", "timeout", true, "图片分类服务调用超时");
    }
    if (error instanceof org.springframework.web.reactive.function.client.WebClientRequestException) {
      Throwable cause = error;
      for (int i = 0; cause != null && i < 12; i++, cause = cause.getCause()) {
        if (cause instanceof javax.net.ssl.SSLException
            || cause instanceof java.net.UnknownHostException
            || cause.getClass().getSimpleName().contains("SslHandshake")) {
          return new ClassifierFailure("500", "tls_or_dns", false, "图片分类服务TLS或DNS异常");
        }
      }
      return new ClassifierFailure("500", "connection", true, "图片分类服务连接失败");
    }
    return new ClassifierFailure("500", "invalid_response", false, "图片分类服务响应格式错误");
  }

  static Classification parseClassification(JsonNode body) {
    if (!body.path("code").asText().equals("200")) {
      throw new ClassifierFailure("500", "business_error", false, "图片分类服务返回业务错误");
    }
    JsonNode top = body.path("data").path("top1_result");
    if (!top.path("class_name").isTextual()) {
      throw malformedClassification();
    }
    String name = top.path("class_name").asText().strip();
    JsonNode raw = top.path("confidence");
    if (name.isEmpty() || (!raw.isNumber() && !raw.isTextual())) {
      throw malformedClassification();
    }
    return new Classification(name, parseConfidence(raw));
  }

  private static double parseConfidence(JsonNode raw) {
    try {
      String value = raw.asText().strip();
      boolean percent = value.endsWith("%");
      double confidence =
          Double.parseDouble(percent ? value.substring(0, value.length() - 1) : value);
      if (percent || confidence > 1) {
        confidence /= 100;
      }
      if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
        throw malformedClassification();
      }
      return confidence;
    } catch (NumberFormatException e) {
      throw malformedClassification(e);
    }
  }

  private static ClassifierFailure malformedClassification() {
    return new ClassifierFailure("500", "invalid_response", false, "图片分类服务分类字段格式错误");
  }

  private static ClassifierFailure malformedClassification(Throwable cause) {
    return new ClassifierFailure("500", "invalid_response", false, "图片分类服务分类字段格式错误", cause);
  }

  Mono<List<KnowledgeChunk>> search(String query) {
    return ragflow.search(query);
  }

  private Mono<JsonNode> postClassifier(String key, Map<String, String> body) {
    return http.post()
        .uri(AgentSettings.httpUri(settings.classifierUrl))
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-API-Key", key)
        .bodyValue(body)
        .exchangeToMono(ExternalServices::readClassifierResponse)
        .timeout(settings.classifierTimeout);
  }

  private static Mono<JsonNode> readClassifierResponse(ClientResponse response) {
    int status = response.statusCode().value();
    if (!response.statusCode().is2xxSuccessful()) {
      String code = status == 401 ? "401" : status >= 400 && status < 500 ? "400" : "500";
      return Mono.error(
          new ClassifierFailure(
              code,
              status == 401 || status == 403 ? "authentication" : "http_error",
              status >= 500 && status <= 599,
              "图片分类服务返回HTTP " + status));
    }
    return response.bodyToMono(byte[].class)
        .defaultIfEmpty(new byte[0])
        .map(ExternalServices::parseClassifierBody);
  }

  private static JsonNode parseClassifierBody(byte[] bytes) {
    try {
      JsonNode result = Json.parse(bytes);
      if (result == null || !result.isObject()) {
        throw new IllegalArgumentException();
      }
      return result;
    } catch (IllegalArgumentException e) {
      throw new AgentException("500", "图片分类服务响应格式错误", e);
    }
  }
}
