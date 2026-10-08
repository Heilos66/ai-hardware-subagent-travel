package com.heilos.travel;

import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class Json {
  static final JsonMapper MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private Json() {}

  static JsonNode parse(String value) {
    return MAPPER.readTree(value);
  }

  static JsonNode parse(byte[] value) {
    return MAPPER.readTree(value);
  }

  static String write(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(JsonNode value) {
    return MAPPER.convertValue(value, Map.class);
  }

  static int length(String value) {
    return value.codePointCount(0, value.length());
  }

  static String prefix(String value, int count) {
    return value.substring(0, value.offsetByCodePoints(0, Math.min(length(value), count)));
  }
}
