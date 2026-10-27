package com.travel.agent;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpHeaders;

/** Request-local diagnostics; never retain upstream bodies, URLs or exception messages. */
final class ModelFailureDetails {
  private volatile Integer status;
  private volatile String contentType, requestId, retryAfter, finishReason;
  private volatile boolean tokenReceived, textReceived;

  void response(int status, HttpHeaders headers) {
    this.status = status;
    contentType = bounded(headers.getFirst("Content-Type"));
    for (String header : new String[] {"x-request-id", "x-openai-request-id", "x-amzn-requestid",
        "x-hw-request-id", "request-id"}) {
      String value = headers.getFirst(header);
      if (value != null) {
        requestId = bounded(value);
        break;
      }
    }
    retryAfter = bounded(headers.getFirst("Retry-After"));
  }

  void finishReason(String reason) {
    finishReason = bounded(reason);
  }

  void token(ChatResponse part) {
    var output = part.getResult().getOutput();
    boolean text = output.getText() != null && !output.getText().isEmpty();
    textReceived |= text;
    tokenReceived |= text || !((String) output.getMetadata()
        .getOrDefault(CompatibleChatModel.REASONING_DELTA, "")).isEmpty();
  }

  Map<String, Object> fields(Throwable error) {
    var causes = new ArrayList<Throwable>();
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    Throwable next = error;
    while (next != null && causes.size() < 12 && seen.add(next)) {
      causes.add(next);
      next = next.getCause();
    }
    String kind = status != null && (status < 200 || status >= 300) ? "http_error" : null;
    if (kind == null) {
      for (Throwable cause : causes) {
        String name = cause.getClass().getSimpleName();
        if (cause instanceof UnknownHostException) { kind = "dns_error"; break; }
        if (name.equals("ConnectTimeoutException")) { kind = "connect_timeout"; break; }
        if (cause instanceof SSLException || name.contains("SslHandshake")) {
          kind = "tls_error"; break;
        }
        if (cause instanceof ConnectException) { kind = "connect_error"; break; }
        if (cause instanceof TimeoutException || name.equals("ReadTimeoutException")) {
          kind = "timeout"; break;
        }
      }
    }
    if (kind == null && error instanceof AgentException) {
      kind = switch (error.getMessage()) {
        case "回答因长度限制未完整生成" -> "length_truncated";
        case "模型请求调用工具，当前流程不支持，回答未完成" -> "unsupported_tool_call";
        case "模型返回未知结束原因，回答未完成" -> "unknown_finish_reason";
        case "模型流式响应中断，未收到结束标记" -> "stream_incomplete";
        case "模型响应message.content为空" -> "empty_response";
        case "模型回答被内容过滤" -> "content_filter";
        case "模型服务返回业务错误", "模型服务返回流式错误" -> "upstream_error";
        default -> "invalid_response";
      };
    }
    if (kind == null && causes.stream().anyMatch(c ->
        c instanceof com.fasterxml.jackson.core.JacksonException
            || c instanceof IllegalArgumentException)) kind = "invalid_response";
    if (kind == null) kind = status == null ? "request_error" : "response_error";
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("http_status", status);
    fields.put("content_type", contentType);
    fields.put("request_id", requestId);
    fields.put("retry_after", retryAfter);
    fields.put("phase", status == null ? "request" : "response");
    fields.put("response_mode", contentType == null ? "unknown"
        : contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream") ? "sse" : "json_or_other");
    fields.put("token_received", tokenReceived);
    fields.put("text_received", textReceived);
    fields.put("failure_kind", kind);
    fields.put("finish_reason", finishReason);
    fields.put("error_type", error.getClass().getSimpleName());
    fields.put("root_cause_type", next == null ? causes.getLast().getClass().getSimpleName() : null);
    fields.put("cause_types", causes.stream().map(c -> c.getClass().getSimpleName()).toList());
    fields.put("cause_chain_truncated", next != null);
    // Only application-authored messages are safe to publish; raw upstream exceptions may embed secrets.
    fields.put("reason", error instanceof AgentException ? error.getMessage() : "模型调用异常");
    return fields;
  }

  private static String bounded(String value) {
    return value == null ? null : Json.prefix(value.replaceAll("[\\p{Cntrl}]", " "), 256);
  }
}
