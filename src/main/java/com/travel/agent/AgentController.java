package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
final class AgentController {
  private final WorkflowEvents events;
  private final AgentSettings settings;

  AgentController(WorkflowEvents events, AgentSettings settings) {
    this.events = events;
    this.settings = settings;
  }

  @GetMapping("/ping")
  Map<String, String> ping() {
    return Map.of("status", "Healthy");
  }

  @PostMapping({"/invocations", "/stream", "/invocations/stream"})
  Mono<Void> invoke(ServerWebExchange exchange) {
    String contentType = exchange.getRequest().getHeaders().getFirst("Content-Type");
    if (contentType == null
        || !contentType
            .split(";", 2)[0]
            .strip()
            .toLowerCase(Locale.ROOT)
            .equals("application/json")) {
      return error(exchange, 415, "需要application/json");
    }
    return DataBufferUtils.join(exchange.getRequest().getBody(), settings.maxRequestBytes)
        .map(
            buffer -> {
              try {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                return bytes;
              } finally {
                DataBufferUtils.release(buffer);
              }
            })
        .defaultIfEmpty(new byte[0])
        .flatMap(
            bytes -> {
              JsonNode payload;
              try {
                payload = Json.parse(bytes);
                if (payload == null || payload.isMissingNode()) {
                  throw new IllegalArgumentException();
                }
              } catch (RuntimeException e) {
                return error(exchange, 400, "请求体不是有效JSON");
              }
              var response = exchange.getResponse();
              response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
              response.getHeaders().set("Cache-Control", "no-cache");
              response.getHeaders().set("X-Accel-Buffering", "no");
              // One flush per event. A disconnect cancels the whole reactive request chain.
              return response.writeAndFlushWith(
                  events.stream(payload)
                      .map(
                          frame ->
                              Mono.just(
                                  response
                                      .bufferFactory()
                                      .wrap(frame.getBytes(StandardCharsets.UTF_8)))));
            })
        .onErrorResume(DataBufferLimitException.class, e -> error(exchange, 413, "请求体大小超限"));
  }

  private Mono<Void> error(ServerWebExchange exchange, int status, String message) {
    var response = exchange.getResponse();
    response.setStatusCode(HttpStatusCode.valueOf(status));
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    byte[] body =
        Json.write(Map.of("code", String.valueOf(status), "message", message)).getBytes(StandardCharsets.UTF_8);
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
  }
}
