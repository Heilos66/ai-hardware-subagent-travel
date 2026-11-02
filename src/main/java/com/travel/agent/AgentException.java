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

  /** @return the stable error code carried by this exception. */
  public String code() {
    return code;
  }

  /** @return a code+message map suitable for client error responses. */
  public Map<String, Object> result() {
    return Map.of("code", code, "message", getMessage());
  }

  /** @return a 400 bad-input exception. */
  public static AgentException badInput() {
    return new AgentException("400", "请求参数格式错误");
  }

  /** @return a 400 bad-input exception preserving the original cause. */
  public static AgentException badInput(Throwable cause) {
    return new AgentException("400", "请求参数格式错误", cause);
  }

  /** @return an E5003 model-failure exception with the given message. */
  public static AgentException model(String message) {
    return new AgentException("E5003", message);
  }
}
