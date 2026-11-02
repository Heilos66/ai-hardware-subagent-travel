package com.travel.agent;

import java.util.Map;

/**
 * Application exception carrying a stable error code for clients.
 */
public final class AgentException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;

  public AgentException(String code, String message) {
    super(message);
    this.code = code;
  }

  public AgentException(String code, String message, Throwable cause) {
    super(message, cause);
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

  public static AgentException badInput(Throwable cause) {
    return new AgentException("400", "请求参数格式错误", cause);
  }

  public static AgentException model(String message) {
    return new AgentException("E5003", message);
  }
}
