package com.heilos.travel;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

record Inputs(
    String query, List<String> imageUrls, List<String> imageBase64, List<ModelConfig> models) {
  record ModelConfig(
      String type,
      String provider,
      URI endpoint,
      String model,
      double temperature,
      String token,
      Map<String, Object> chatTemplateKwargs,
      Map<String, Object> thinking) {
    @Override
    public String toString() {
      return "ModelConfig[redacted]";
    }
  }

  static Inputs parse(JsonNode raw) {
    try {
      String query = string(raw, "query", "");
      List<String> urls = strings(raw, "image_url");
      List<String> encoded = strings(raw, "image_base64");
      List<ModelConfig> configs = new ArrayList<>();
      JsonNode models = raw.path("model_config");
      if (!models.isMissingNode() && !models.isArray()) throw AgentException.badInput();
      for (JsonNode model : models) {
        if (!model.isObject()) throw AgentException.badInput();
        String type = string(model, "type", null);
        if (!List.of("text", "text_vision").contains(type)) throw AgentException.badInput();
        String provider = required(model, "provider");
        String name = required(model, "model_name");
        String token = required(model, "auth_token");
        if (token.contains("\r") || token.contains("\n")) throw AgentException.badInput();
        JsonNode temp = model.path("temperature");
        double temperature = .1;
        if (!temp.isMissingNode()) {
          if (!temp.isNumber() && !temp.isTextual()) throw AgentException.badInput();
          temperature = Double.parseDouble(temp.asText());
          if (!Double.isFinite(temperature)) throw AgentException.badInput();
        }
        configs.add(
            new ModelConfig(
                type,
                provider,
                AgentSettings.httpUri(required(model, "endpoint")),
                name,
                temperature,
                token,
                options(model.get("chat_template_kwargs")),
                options(model.get("thinking"))));
      }
      return new Inputs(query, urls, encoded, List.copyOf(configs));
    } catch (RuntimeException e) {
      throw AgentException.badInput();
    }
  }

  ModelConfig select(String type) {
    return models.stream()
        .filter(m -> m.type().equals(type))
        .findFirst()
        .orElseThrow(() -> new AgentException("400", "模型参数缺少：" + type));
  }

  private static String string(JsonNode node, String key, String fallback) {
    JsonNode value = node.get(key);
    if (value == null && fallback != null) return fallback;
    if (value == null || !value.isTextual()) throw AgentException.badInput();
    return value.asText();
  }

  private static String required(JsonNode node, String key) {
    String value = string(node, key, null);
    if (value.isBlank()) throw AgentException.badInput();
    // Reject newline-bearing credentials before trimming.
    if (key.equals("auth_token") && (value.contains("\r") || value.contains("\n")))
      throw AgentException.badInput();
    return value.strip();
  }

  private static List<String> strings(JsonNode node, String key) {
    JsonNode values = node.get(key);
    if (values == null) return List.of();
    if (!values.isArray()) throw AgentException.badInput();
    List<String> result = new ArrayList<>();
    for (JsonNode value : values) {
      if (!value.isTextual()) throw AgentException.badInput();
      result.add(value.asText());
    }
    return List.copyOf(result);
  }

  private static Map<String, Object> options(JsonNode value) {
    if (value == null || value.isNull()) return null;
    if (value.isTextual()) value = Json.parse(value.asText());
    if (value == null || !value.isObject()) throw AgentException.badInput();
    return Json.object(value);
  }
}
