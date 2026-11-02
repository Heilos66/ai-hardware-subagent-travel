package com.travel.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.Map;

final class Json {
  static final JsonMapper MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private Json() {}

  static JsonNode parse(String value) {
    try {
      return MAPPER.readTree(value);
    } catch (JsonProcessingException e) {
      // Cause retained for diagnostics; callers do not log raw parser messages.
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  static JsonNode parse(byte[] value) {
    try {
      return MAPPER.readTree(value);
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("JSON serialization failed", e);
    }
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
