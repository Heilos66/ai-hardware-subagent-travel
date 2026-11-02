package com.travel.agent;

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
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

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
              StringBuilder text = new StringBuilder();
              StringBuilder reasoning = new StringBuilder();
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
              && !settings.modelAllowedHosts.contains(endpoint.getHost().toLowerCase(Locale.ROOT))) {
            return Flux.error(new AgentException("400", "模型endpoint不在允许的主机列表中"));
          }
          Map<String, Object> request = buildRequest(prompt);
          boolean direct = config.provider().equalsIgnoreCase("openai-compatible");
          RequestTarget target = buildTarget(endpoint, request, direct);
          WebClient.RequestBodySpec call = buildCallSpec(target, direct);
          diagnostics.modelRequest(context, config, target.body());
          return call.bodyValue(target.body())
              .exchangeToFlux(reply -> handleReply(reply, context, failure))
              .timeout(
                  Mono.delay(settings.modelTimeout),
                  part -> Mono.delay(Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))))
              .doOnError(error -> diagnostics.modelFailure(context, config, failure, error))
              .onErrorMap(this::mapError);
        });
  }

  private Map<String, Object> buildRequest(Prompt prompt) {
    Map<String, Object> request = new LinkedHashMap<>();
    ChatOptions options =
        prompt.getOptions() == null ? getDefaultOptions() : prompt.getOptions();
    request.put("model", options.getModel() == null ? config.model() : options.getModel());
    request.put("messages", messages(prompt));
    request.put(
        "temperature",
        options.getTemperature() == null ? config.temperature() : options.getTemperature());
    request.put("stream", true);
    if (config.chatTemplateKwargs() != null) {
      request.put("chat_template_kwargs", config.chatTemplateKwargs());
    }
    if (config.thinking() != null) {
      request.put("thinking", config.thinking());
    }
    return request;
  }

  private RequestTarget buildTarget(URI endpoint, Map<String, Object> request, boolean direct) {
    if (direct) {
      return new RequestTarget(endpoint, request);
    }
    URI proxy = proxyEndpoint(config);
    Object body =
        Map.of(
            "temp_token", config.token(),
            "action",
            config.type().equals("text_vision") ? "invoke_text_vision" : "invoke_text",
            "model_request", request);
    return new RequestTarget(proxy, body);
  }

  private WebClient.RequestBodySpec buildCallSpec(RequestTarget target, boolean direct) {
    WebClient.RequestBodySpec call =
        http.post()
            .uri(target.endpoint())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .header("User-Agent", "travel-agent/2.0");
    if (direct) {
      call.header(
          "Authorization",
          config.token().toLowerCase(Locale.ROOT).startsWith("bearer ")
              ? config.token()
              : "Bearer " + config.token());
    }
    return call;
  }

  private Flux<ChatResponse> handleReply(
      ClientResponse reply, ContextView context, ModelFailureDetails failure) {
    failure.response(reply.statusCode().value(), reply.headers().asHttpHeaders());
    if (!reply.statusCode().is2xxSuccessful()) {
      return Flux.error(
          AgentException.model("模型服务返回HTTP " + reply.statusCode().value()));
    }
    MediaType type = reply.headers().contentType().orElse(MediaType.APPLICATION_JSON);
    boolean streaming = MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type);
    Flux<ChatResponse> responses =
        streaming ? streamResponses(reply, failure) : jsonResponses(reply);
    // Observe the first parsed upstream token before Spring AI/SSE forwarding.
    return responses.doOnNext(
        part -> {
          failure.token(part);
          context
              .<WorkflowTiming>getOrEmpty(WorkflowTiming.class)
              .ifPresent(
                  timing -> {
                    var output = part.getResult().getOutput();
                    timing.firstToken(
                        !output.getText().isEmpty(),
                        !((String) output.getMetadata().getOrDefault(REASONING_DELTA, ""))
                            .isEmpty(),
                        streaming);
                  });
        });
  }

  private Flux<ChatResponse> streamResponses(ClientResponse reply, ModelFailureDetails failure) {
    StreamState state = new StreamState(failure);
    return SseDecoder.decode(reply.bodyToFlux(DataBuffer.class))
        .takeUntil(event -> event.data().strip().equals("[DONE]"))
        .concatMap(state::decode, 1)
        .concatWith(Flux.defer(state::finish));
  }

  private Flux<ChatResponse> jsonResponses(ClientResponse reply) {
    return reply
        .bodyToMono(byte[].class)
        .defaultIfEmpty(new byte[0])
        .map(bytes -> normalize(Json.parse(bytes)))
        .flux();
  }

  private Throwable mapError(Throwable e) {
    if (e instanceof AgentException) {
      return e;
    }
    return AgentException.model(
        e instanceof TimeoutException ? "模型服务调用超时" : "模型服务连接失败或响应格式错误");
  }

  private record RequestTarget(URI endpoint, Object body) {}

  private static final java.util.regex.Pattern TRAILING_SLASH = java.util.regex.Pattern.compile("/+$");
  private static final java.util.regex.Pattern MODEL_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9._-]+");

  static URI proxyEndpoint(Inputs.ModelConfig config) {
    URI uri = config.endpoint();
    String path = TRAILING_SLASH.matcher(uri.getRawPath()).replaceAll("");
    if (path.endsWith("/invocations")) {
      path += "/stream";
    } else if (!path.endsWith("/invocations/stream")) {
      if (!MODEL_NAME.matcher(config.model()).matches()) {
        throw new AgentException("400", "model_name包含不支持的字符");
      }
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
    List<Map<String, Object>> result = new ArrayList<>(prompt.getInstructions().size());
    for (Message message : prompt.getInstructions()) {
      Object content = message.getText() == null ? "" : message.getText();
      if (message instanceof UserMessage user && !user.getMedia().isEmpty()) {
        List<Map<String, Object>> parts = new ArrayList<>(user.getMedia().size() + 1);
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
    if (body == null || !body.isObject()) {
      throw AgentException.model("模型响应必须是JSON对象");
    }
    boolean legacy = body.has("code");
    JsonNode message = legacy ? legacyMessage(body) : body.path("choices").path(0).path("message");
    JsonNode text = message.path(legacy ? "message" : "content");
    if (!text.isTextual() || text.asText().isBlank()) {
      throw AgentException.model("模型响应message.content为空或格式错误");
    }
    return response(text.asText(), optionalText(message.get("reasoning_content")));
  }

  private static JsonNode legacyMessage(JsonNode body) {
    JsonNode code = body.get("code");
    if (!((code.isNumber() && code.asDouble() == 0)
        || (code.isTextual() && code.asText().equals("0")))) {
      throw AgentException.model("模型服务返回业务错误");
    }
    return body;
  }

  private static String optionalText(JsonNode node) {
    if (node == null || node.isNull()) {
      return "";
    }
    if (!node.isTextual()) {
      throw AgentException.model("模型流式文本格式错误");
    }
    return node.asText();
  }

  private static ChatResponse response(String text, String reasoning) {
    return new ChatResponse(
        List.of(new Generation(new AssistantMessage(text, Map.of(REASONING_DELTA, reasoning)))));
  }

  private static final class StreamState {
    private boolean completed;
    private boolean hasText;
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
      JsonNode chunk = parseChunk(event);
      if (chunk == null) {
        return List.of();
      }
      return processChoices(chunk.path("choices"));
    }

    private JsonNode parseChunk(SseDecoder.Event event) {
      if (event.name().equals("error")) {
        throw AgentException.model("模型服务返回流式错误");
      }
      String data = event.data().strip();
      if (data.equals("[DONE]")) {
        completed = true;
        return null;
      }
      if (data.isBlank()) {
        return null;
      }
      return validateChunk(Json.parse(data));
    }

    private static JsonNode validateChunk(JsonNode chunk) {
      if (chunk == null || !chunk.isObject()) {
        throw AgentException.model("模型流式数据格式错误");
      }
      if (chunk.has("error") || chunk.has("code")) {
        throw AgentException.model("模型服务返回流式错误");
      }
      JsonNode choices = chunk.path("choices");
      if (choices.isArray() && choices.isEmpty() && chunk.has("usage")) {
        return null;
      }
      if (!choices.isArray() || choices.isEmpty()) {
        throw AgentException.model("模型流式数据缺少choices");
      }
      return chunk;
    }

    private List<ChatResponse> processChoices(JsonNode choices) {
      List<ChatResponse> result = new ArrayList<>();
      for (JsonNode choice : choices) {
        processChoice(choice, result);
        if (terminalError != null) {
          break;
        }
      }
      return result;
    }

    private void processChoice(JsonNode choice, List<ChatResponse> result) {
      JsonNode delta = choiceDelta(choice);
      if (delta == null) {
        return;
      }
      JsonNode finish = choice.path("finish_reason");
      String reason = finish.isTextual() ? finish.asText() : "";
      String text = optionalText(delta.get("content"));
      String reasoning = optionalText(delta.get("reasoning_content"));
      hasText |= !text.isBlank();
      if (!text.isEmpty() || !reasoning.isEmpty()) {
        result.add(response(text, reasoning));
      }
      applyFinishReason(reason);
    }

    private static JsonNode choiceDelta(JsonNode choice) {
      if (!choice.isObject()) {
        throw AgentException.model("模型流式choice格式错误");
      }
      if (choice.path("index").asInt(0) != 0) {
        return null;
      }
      JsonNode delta = choice.path("delta");
      if (!delta.isObject()) {
        throw AgentException.model("模型流式数据缺少delta");
      }
      return delta;
    }

    private void applyFinishReason(String reason) {
      if (reason.isEmpty()) {
        return;
      }
      failure.finishReason(reason);
      if (reason.equals("content_filter")) {
        throw AgentException.model("模型回答被内容过滤");
      }
      if (reason.equals("stop")) {
        completed = true;
        return;
      }
      terminalError = AgentException.model(switch (reason) {
        case "length" -> "回答因长度限制未完整生成";
        case "tool_calls", "function_call" -> "模型请求调用工具，当前流程不支持，回答未完成";
        default -> "模型返回未知结束原因，回答未完成";
      });
    }

    Flux<ChatResponse> finish() {
      if (!completed) {
        return Flux.error(AgentException.model("模型流式响应中断，未收到结束标记"));
      }
      if (!hasText) {
        return Flux.error(AgentException.model("模型响应message.content为空"));
      }
      return Flux.empty();
    }
  }
}
