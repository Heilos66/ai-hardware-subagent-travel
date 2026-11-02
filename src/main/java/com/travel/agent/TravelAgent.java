package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

final class TravelAgent {
  sealed interface Step permits Stage, Delta, Result {}

  record Stage(String id, String name, String type) implements Step {}

  record Delta(String text, String reasoning) implements Step {}

  record Result(Map<String, Object> content) implements Step {}

  private final AgentSettings settings;
  private final ExternalServices services;
  private final WebClient http;
  private final Prompts.Limits promptLimits;

  TravelAgent(AgentSettings settings, ExternalServices services, WebClient http) {
    this.settings = settings;
    this.services = services;
    this.http = http;
    this.promptLimits = new Prompts.Limits(settings.maxContextChars, settings.maxResponseChars);
  }

  Flux<Step> run(JsonNode payload) {
    return Flux.concat(
        Flux.just(new Stage("validate", "参数校验", "Code")), Flux.defer(() -> route(payload)));
  }

  private Flux<Step> route(JsonNode payload) {
    JsonNode raw = inputsObject(payload);
    return switch (operationFrom(raw)) {
      case "", "chat_completions" -> routeChat(raw);
      case "query_capabilities" -> capabilities();
      case "create_response", "fetch_response" ->
          Flux.error(new AgentException("400", "能力不支持"));
      default -> Flux.error(new AgentException("400", "参数非法"));
    };
  }

  private Flux<Step> routeChat(JsonNode raw) {
    Inputs inputs = validateAndParseInputs(raw);
    ImageInput image = ImageInput.prepare(inputs, settings.maxImageBytes);
    String query = inputs.query().strip();
    if (!image.exists() && query.isEmpty()) {
      throw new AgentException("400", "未提供图片时query不能为空");
    }
    if (!image.exists()) {
      return routeText(inputs, query);
    }
    return routeImage(inputs, query, image);
  }

  private static JsonNode inputsObject(JsonNode payload) {
    if (payload == null || !payload.isObject() || !payload.path("inputs").isObject()) {
      throw new AgentException("400", "请求必须包含inputs对象");
    }
    return payload.get("inputs");
  }

  private static String operationFrom(JsonNode raw) {
    JsonNode op = raw.get("operation");
    if (op != null && !op.isNull() && !op.isTextual()) {
      throw AgentException.badInput();
    }
    return op == null || op.isNull() ? "" : op.asText();
  }

  private static Flux<Step> capabilities() {
    return Flux.just(
        new Result(
            Map.of(
                "capabilities",
                Map.of("chat_completions", true, "create_response", false, "fetch_response", false))));
  }

  private Inputs validateAndParseInputs(JsonNode raw) {
    Inputs inputs = Inputs.parse(raw);
    if (inputs.models().isEmpty()) {
      throw new AgentException("400", "模型参数缺少");
    }
    if (Json.length(inputs.query()) > settings.maxQueryChars) {
      throw new AgentException("400", "文本长度不能大于" + settings.maxQueryChars + "字");
    }
    return inputs;
  }

  private Flux<Step> routeText(Inputs inputs, String query) {
    Inputs.ModelConfig model = inputs.select("text");
    return Flux.concat(
        Flux.just(new Stage("search_text", "知识检索_文本", "KnowledgeRepo")),
        services
            .search(query)
            .flatMapMany(
                chunks ->
                    generate(
                        model,
                        Prompts.build(query, chunks, null, "", promptLimits))));
  }

  private Flux<Step> routeImage(Inputs inputs, String query, ImageInput image) {
    return Flux.concat(
        Flux.just(new Stage("classify", "图片分类", "Http")),
        services
            .classify(image)
            .flatMapMany(
                classification -> routeByClassification(inputs, query, image, classification)));
  }

  private Flux<Step> routeByClassification(Inputs inputs, String query, ImageInput image,
      ExternalServices.Classification classification) {
    if (classification.status() == ExternalServices.ClassificationStatus.RECOGNIZED) {
      Inputs.ModelConfig model = inputs.select("text");
      return Flux.concat(
          Flux.just(new Stage("search_image", "知识检索_图片", "KnowledgeRepo")),
          services
              .search("检索关于" + classification.name() + "的文档")
              .flatMapMany(
                  chunks ->
                      generate(
                          model,
                          Prompts.build(
                              query.isEmpty() ? "请简要介绍这个景点或展品" : query,
                              chunks,
                              null,
                              classification.name(),
                              promptLimits))));
    }
    return generate(
        inputs.select("text_vision"),
        Prompts.build(query, List.of(), image, "", promptLimits));
  }

  private Flux<Step> generate(Inputs.ModelConfig config, Prompt prompt) {
    return Flux.defer(
        () -> {
          StringBuilder answer = new StringBuilder();
          ChatClient chat = ChatClient.create(new CompatibleChatModel(http, settings, config));
          Flux<Step> deltas =
              chat.prompt(prompt).stream()
                  .chatResponse()
                  .<Step>handle(
                      (response, sink) -> {
                        if (response.getResult() == null) {
                          return;
                        }
                        var message = response.getResult().getOutput();
                        String text = message.getText() == null ? "" : message.getText();
                        String reasoning =
                            (String)
                                message
                                    .getMetadata()
                                    .getOrDefault(CompatibleChatModel.REASONING_DELTA, "");
                        if (!text.isEmpty() || !reasoning.isEmpty()) {
                          answer.append(text);
                          sink.next(new Delta(text, reasoning));
                        }
                      });
          return Flux.concat(
              Flux.just(new Stage("model", "模型请求", "Http")),
              deltas,
              Flux.defer(
                  () -> Flux.just(new Result(Map.of("code", "0", "message", answer.toString())))));
        });
  }
}
