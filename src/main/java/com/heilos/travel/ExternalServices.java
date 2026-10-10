package com.heilos.travel;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

final class ExternalServices {
  record Classification(String name, double confidence) {
    static Classification empty() {
      return new Classification("", 0);
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
    return Mono.defer(
        () -> {
          String key = settings.classifierKey.strip();
          if (key.isEmpty() || key.startsWith("E1-"))
            return Mono.error(new AgentException("E5004", "图片分类服务密钥未配置或仍为平台加密值"));
          Map<String, String> body =
              Map.of("image_url", image.url(), "image_base64", image.base64());
          return postClassifier(key, body)
              .map(ExternalServices::parseClassification);
        });
  }

  static Classification parseClassification(JsonNode body) {
    if (!body.path("code").asText().equals("200")) return Classification.empty();
    JsonNode top = body.path("data").path("top1_result");
    if (!top.path("class_name").isTextual()) return Classification.empty();
    String name = top.path("class_name").asText().strip();
    JsonNode raw = top.path("confidence");
    if (name.isEmpty() || (!raw.isNumber() && !raw.isTextual())) return Classification.empty();
    try {
      String value = raw.asText().strip();
      boolean percent = value.endsWith("%");
      double confidence =
          Double.parseDouble(percent ? value.substring(0, value.length() - 1) : value);
      if (percent || confidence > 1) confidence /= 100;
      return !Double.isFinite(confidence) || confidence < 0 || confidence > 1
          ? Classification.empty()
          : new Classification(name, confidence);
    } catch (NumberFormatException e) {
      return Classification.empty();
    }
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
        .exchangeToMono(
            response -> {
              int status = response.statusCode().value();
              if (!response.statusCode().is2xxSuccessful()) {
                String code = status == 401 ? "401" : status >= 400 && status < 500 ? "400" : "500";
                return Mono.error(new AgentException(code, "图片分类服务返回HTTP " + status));
              }
              return response
                  .bodyToMono(byte[].class)
                  .defaultIfEmpty(new byte[0])
                  .map(
                      bytes -> {
                        try {
                          JsonNode result = Json.parse(bytes);
                          if (result == null || !result.isObject())
                            throw new IllegalArgumentException();
                          return result;
                        } catch (RuntimeException e) {
                          throw new AgentException("500", "图片分类服务响应格式错误");
                        }
                      });
            })
        .timeout(settings.classifierTimeout)
        .onErrorMap(
            e ->
                e instanceof AgentException
                    ? e
                    : new AgentException(
                        "500",
                        "图片分类服务" + (e instanceof TimeoutException ? "调用超时" : "连接失败或响应异常")));
  }
}
