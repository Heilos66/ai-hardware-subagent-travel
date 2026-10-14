package com.heilos.travel;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Spring AI provider adapter for the existing full-URL and legacy proxy contracts. This adapter's
 * reasoningContent metadata is an upstream delta. Instances are request-scoped: no shared mutable
 * credentials or conversation state.
 */
final class CompatibleChatModel implements ChatModel {
  static final String REASONING_DELTA = "reasoningContent";
  private final WebClient http;
  private final AgentSettings settings;
  private final Inputs.ModelConfig config;

  CompatibleChatModel(WebClient http, AgentSettings settings, Inputs.ModelConfig config) {
    this.http = http;
    this.settings = settings;
    this.config = config;
  }

  @Override
  public ChatOptions getDefaultOptions() {
    return ChatOptions.builder().model(config.model()).temperature(config.temperature()).build();
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    return stream(prompt)
        .collectList()
        .map(
            parts -> {
              StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
              for (ChatResponse part : parts) {
                text.append(part.getResult().getOutput().getText());
                reasoning.append(
                    part.getResult().getOutput().getMetadata().getOrDefault(REASONING_DELTA, ""));
              }
              return response(text.toString(), reasoning.toString());
            })
        .block(settings.modelTimeout.plusSeconds(1));
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.deferContextual(
        context -> {
          ModelFailureDetails failure = new ModelFailureDetails();
          WorkflowDiagnostics diagnostics = new WorkflowDiagnostics(settings);
          long deadline = System.nanoTime() + settings.modelTimeout.toNanos();
          URI endpoint = config.endpoint();
          if (!settings.modelAllowedHosts.isEmpty()
              && !settings.modelAllowedHosts.contains(endpoint.getHost().toLowerCase(Locale.ROOT)))
            return Flux.error(new AgentException("400", "模型endpoint不在允许的主机列表中"));
          Map<String, Object> request = new LinkedHashMap<>();
          ChatOptions options =
              prompt.getOptions() == null ? getDefaultOptions() : prompt.getOptions();
          request.put("model", options.getModel() == null ? config.model() : options.getModel());
          request.put("messages", messages(prompt));
          request.put(
              "temperature",
              options.getTemperature() == null ? config.temperature() : options.getTemperature());
          request.put("stream", true);
          if (config.chatTemplateKwargs() != null)
            request.put("chat_template_kwargs", config.chatTemplateKwargs());
          if (config.thinking() != null) request.put("thinking", config.thinking());
          boolean direct = config.provider().equalsIgnoreCase("openai-compatible");
          Object body = request;
          if (!direct) {
            endpoint = proxyEndpoint(config);
            body =
                Map.of(
                    "temp_token",
                    config.token(),
                    "action",
                    config.type().equals("text_vision") ? "invoke_text_vision" : "invoke_text",
                    "model_request",
                    request);
          }
          WebClient.RequestBodySpec call =
              http.post()
                  .uri(endpoint)
                  .contentType(MediaType.APPLICATION_JSON)
                  .accept(MediaType.TEXT_EVENT_STREAM)
                  .header("User-Agent", "travel-agent/2.0");
          if (direct)
            call.header(
                "Authorization",
                config.token().toLowerCase(Locale.ROOT).startsWith("bearer ")
                    ? config.token()
                    : "Bearer " + config.token());
          diagnostics.modelRequest(context, config, body);
          return call.bodyValue(body)
              .exchangeToFlux(
                  reply -> {
                    failure.response(reply.statusCode().value(), reply.headers().asHttpHeaders());
                    if (!reply.statusCode().is2xxSuccessful())
                      return Flux.error(
                          AgentException.model("模型服务返回HTTP " + reply.statusCode().value()));
                    MediaType type =
                        reply.headers().contentType().orElse(MediaType.APPLICATION_JSON);
                    boolean streaming = MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type);
                    Flux<ChatResponse> responses;
                    if (streaming) {
                      StreamState state = new StreamState(failure);
                      responses = SseDecoder.decode(reply.bodyToFlux(DataBuffer.class))
                          .takeUntil(event -> event.data().strip().equals("[DONE]"))
                          .concatMap(state::decode, 1)
                          .concatWith(Flux.defer(state::finish));
                    } else responses = reply
                        .bodyToMono(byte[].class)
                        .defaultIfEmpty(new byte[0])
                        .map(bytes -> normalize(Json.parse(bytes)))
                        .flux();
                    // Observe the first parsed upstream token before Spring AI/SSE forwarding.
                    return responses.doOnNext(part -> {
                      failure.token(part);
                      context
                        .<WorkflowTiming>getOrEmpty(WorkflowTiming.class)
                        .ifPresent(timing -> {
                          var output = part.getResult().getOutput();
                          timing.firstToken(!output.getText().isEmpty(),
                              !((String) output.getMetadata().getOrDefault(REASONING_DELTA, "")).isEmpty(),
                              streaming ? "sse" : "json");
                        });
                    });
                    // Every timeout uses the same deadline, even when chunks keep arriving.
                    // timeout() cancels the HTTP publisher before emitting the timeout error.
                  })
              .timeout(
                  Mono.delay(settings.modelTimeout),
                  part -> Mono.delay(Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))))
              .doOnError(error -> diagnostics.modelFailure(context, config, failure, error))
              .onErrorMap(
                  e ->
                      e instanceof AgentException
                          ? e
                          : AgentException.model(
                              e instanceof TimeoutException ? "模型服务调用超时" : "模型服务连接失败或响应格式错误"));
        });
  }

  static URI proxyEndpoint(Inputs.ModelConfig config) {
    URI uri = config.endpoint();
    String path = uri.getRawPath().replaceAll("/+$", "");
    if (path.endsWith("/invocations")) path += "/stream";
    else if (!path.endsWith("/invocations/stream")) {
      if (!config.model().matches("[A-Za-z0-9._-]+"))
        throw new AgentException("400", "model_name包含不支持的字符");
      path += "/runtimes/" + config.model() + "/invocations/stream";
    }
    return URI.create(
        uri.getScheme()
            + "://"
            + uri.getRawAuthority()
            + path
            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
  }

  private static List<Map<String, Object>> messages(Prompt prompt) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Message message : prompt.getInstructions()) {
      Object content = message.getText() == null ? "" : message.getText();
      if (message instanceof UserMessage user && !user.getMedia().isEmpty()) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", content));
        for (Media media : user.getMedia()) {
          Object data = media.getData();
          String url =
              data instanceof byte[] bytes
                  ? "data:"
                      + media.getMimeType()
                      + ";base64,"
                      + Base64.getEncoder().encodeToString(bytes)
                  : data.toString();
          parts.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
        }
        content = parts;
      }
      result.add(Map.of("role", message.getMessageType().getValue(), "content", content));
    }
    return result;
  }

  static ChatResponse normalize(JsonNode body) {
    if (body == null || !body.isObject()) throw AgentException.model("模型响应必须是JSON对象");
    JsonNode message;
    if (body.has("code")) {
      JsonNode code = body.get("code");
      if (!((code.isNumber() && code.asDouble() == 0)
          || (code.isTextual() && code.asText().equals("0"))))
        throw AgentException.model("模型服务返回业务错误");
      message = body;
    } else message = body.path("choices").path(0).path("message");
    JsonNode text = message.path(body.has("code") ? "message" : "content");
    if (!text.isTextual() || text.asText().isBlank())
      throw AgentException.model("模型响应message.content为空或格式错误");
    return response(text.asText(), optionalText(message.get("reasoning_content")));
  }

  private static String optionalText(JsonNode node) {
    if (node == null || node.isNull()) return "";
    if (!node.isTextual()) throw AgentException.model("模型流式文本格式错误");
    return node.asText();
  }

  private static ChatResponse response(String text, String reasoning) {
    return new ChatResponse(
        List.of(new Generation(new AssistantMessage(text, Map.of(REASONING_DELTA, reasoning)))));
  }

  private static final class StreamState {
    private boolean completed, hasText;
    private final ModelFailureDetails failure;
    private AgentException terminalError;

    StreamState(ModelFailureDetails failure) {
      this.failure = failure;
    }

    Flux<ChatResponse> decode(SseDecoder.Event event) {
      // Deliver text in the terminating frame before signalling an incomplete answer.
      return Flux.fromIterable(accept(event))
          .concatWith(Flux.defer(() -> terminalError == null
              ? Flux.empty() : Flux.error(terminalError)));
    }

    List<ChatResponse> accept(SseDecoder.Event event) {
      if (event.name().equals("error")) throw AgentException.model("模型服务返回流式错误");
      if (event.data().strip().equals("[DONE]")) {
        completed = true;
        return List.of();
      }
      if (event.data().isBlank()) return List.of();
      JsonNode chunk = Json.parse(event.data());
      if (chunk == null || !chunk.isObject()) throw AgentException.model("模型流式数据格式错误");
      if (chunk.has("error") || chunk.has("code")) throw AgentException.model("模型服务返回流式错误");
      JsonNode choices = chunk.path("choices");
      if (choices.isArray() && choices.isEmpty() && chunk.has("usage")) return List.of();
      if (!choices.isArray() || choices.isEmpty()) throw AgentException.model("模型流式数据缺少choices");
      List<ChatResponse> result = new ArrayList<>();
      for (JsonNode choice : choices) {
        if (!choice.isObject()) throw AgentException.model("模型流式choice格式错误");
        if (choice.path("index").asInt(0) != 0) continue;
        JsonNode delta = choice.path("delta");
        if (!delta.isObject()) throw AgentException.model("模型流式数据缺少delta");
        JsonNode finish = choice.path("finish_reason");
        String reason = finish.isTextual() ? finish.asText() : "";
        if (!reason.isEmpty()) failure.finishReason(reason);
        if (reason.equals("content_filter")) throw AgentException.model("模型回答被内容过滤");
        String text = optionalText(delta.get("content")),
            reasoning = optionalText(delta.get("reasoning_content"));
        hasText |= !text.isBlank();
        if (!text.isEmpty() || !reasoning.isEmpty()) result.add(response(text, reasoning));
        if (reason.equals("stop")) completed = true;
        else if (!reason.isEmpty()) {
          terminalError = AgentException.model(switch (reason) {
            case "length" -> "回答因长度限制未完整生成";
            case "tool_calls", "function_call" -> "模型请求调用工具，当前流程不支持，回答未完成";
            default -> "模型返回未知结束原因，回答未完成";
          });
          break;
        }
      }
      return result;
    }

    Flux<ChatResponse> finish() {
      if (!completed) return Flux.error(AgentException.model("模型流式响应中断，未收到结束标记"));
      if (!hasText) return Flux.error(AgentException.model("模型响应message.content为空"));
      return Flux.empty();
    }
  }
}
