package com.heilos.travel;

import java.util.Map;

public final class AgentException extends RuntimeException {
  private final String code;

  public AgentException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }

  public Map<String, Object> result() {
    return Map.of("code", code, "message", getMessage());
  }

  public static AgentException badInput() {
    return new AgentException("400", "请求参数格式错误");
  }

  public static AgentException model(String message) {
    return new AgentException("E5003", message);
  }
}
