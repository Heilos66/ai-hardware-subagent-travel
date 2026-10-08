package com.heilos.travel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

final class ExternalServices {
  record Classification(String name, double confidence) {
    static Classification empty() {
      return new Classification("", 0);
    }
  }

  record KnowledgeChunk(String content, String title, String subtitle, Double score) {}

  private enum Service {
    CLASSIFIER("图片分类服务", "500"),
    SEARCH("知识检索服务", "E5002");
    final String label, code;

    Service(String label, String code) {
      this.label = label;
      this.code = code;
    }
  }

  private final WebClient http;
  private final AgentSettings settings;

  ExternalServices(WebClient http, AgentSettings settings) {
    this.http = http;
    this.settings = settings;
  }

  Mono<Classification> classify(ImageInput image) {
    return Mono.defer(
        () -> {
          String key = settings.classifierKey.strip();
          if (key.isEmpty() || key.startsWith("E1-"))
            return Mono.error(new AgentException("E5004", "图片分类服务密钥未配置或仍为平台加密值"));
          Map<String, String> body =
              Map.of("image_url", image.url(), "image_base64", image.base64());
          return post(
                  settings.classifierUrl,
                  Map.of("X-API-Key", key),
                  body,
                  settings.classifierTimeout,
                  Service.CLASSIFIER)
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

  Mono<List<KnowledgeChunk>> search(String query, boolean image) {
    return Mono.defer(
        () -> {
          String repo = image ? settings.imageRepo : settings.textRepo;
          if (!AgentSettings.validId(repo))
            return Mono.error(new AgentException("E5004", "Koo Search知识库ID未配置或格式错误"));
          if (query.isBlank() || Json.length(query) > 1000)
            return Mono.error(new AgentException("400", "检索内容必须为1至1000个字符"));
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("repo_id", repo);
          body.put("content", query);
          body.put("scope", "doc");
          body.put("page_num", 1);
          body.put("page_size", settings.topK);
          Double threshold = image ? settings.imageThreshold : settings.textThreshold;
          if (threshold != null) body.put("search_threshold", threshold);
          String url = settings.searchUrl();
          return searchHeaders()
              .flatMap(headers -> post(url, headers, body, settings.searchTimeout, Service.SEARCH))
              .map(this::parseChunks);
        });
  }

  private Mono<Map<String, String>> searchHeaders() {
    if (!settings.appCode.isEmpty()) return Mono.just(Map.of("X-Apig-AppCode", settings.appCode));
    Mono<String> token =
        settings.tokenFile.isEmpty()
            ? Mono.just(settings.searchToken)
            : Mono.fromCallable(() -> Files.readString(Path.of(settings.tokenFile)))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(e -> new AgentException("E5004", "Koo Search Token文件不可读取"));
    return token
        .map(String::strip)
        .map(
            value -> {
              if (value.isEmpty()) throw new AgentException("E5004", "Koo Search认证未配置");
              return Map.of("X-Auth-Token", value);
            });
  }

  private List<KnowledgeChunk> parseChunks(JsonNode body) {
    JsonNode docs = body.path("doc_list");
    if (!docs.isArray()) throw new AgentException("E5002", "知识检索响应缺少doc_list");
    List<KnowledgeChunk> chunks = new ArrayList<>();
    for (int i = 0; i < Math.min(settings.topK, docs.size()); i++) {
      JsonNode doc = docs.get(i);
      if (!doc.isObject() || !doc.path("content").isTextual())
        throw new AgentException("E5002", "知识检索分片格式错误");
      String content = doc.path("content").asText().strip();
      if (content.isEmpty()) continue;
      JsonNode rawScore = doc.path("score");
      Double score =
          rawScore.isNumber() && Double.isFinite(rawScore.asDouble()) ? rawScore.asDouble() : null;
      chunks.add(
          new KnowledgeChunk(
              content, doc.path("title").asText(""), doc.path("subtitle").asText(""), score));
    }
    return List.copyOf(chunks);
  }

  private Mono<JsonNode> post(
      String url, Map<String, String> headers, Object body, Duration timeout, Service service) {
    return http.post()
        .uri(AgentSettings.httpUri(url))
        .contentType(MediaType.APPLICATION_JSON)
        .headers(h -> headers.forEach(h::set))
        .bodyValue(body)
        .exchangeToMono(
            response -> {
              int status = response.statusCode().value();
              if (!response.statusCode().is2xxSuccessful()) {
                String code =
                    service == Service.CLASSIFIER && status == 401
                        ? "401"
                        : service == Service.CLASSIFIER && status >= 400 && status < 500
                            ? "400"
                            : service.code;
                return Mono.error(new AgentException(code, service.label + "返回HTTP " + status));
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
                          throw new AgentException(service.code, service.label + "响应格式错误");
                        }
                      });
            })
        .timeout(timeout)
        .onErrorMap(
            e ->
                e instanceof AgentException
                    ? e
                    : new AgentException(
                        service.code,
                        service.label + (e instanceof TimeoutException ? "调用超时" : "连接失败或响应异常")));
  }
}
