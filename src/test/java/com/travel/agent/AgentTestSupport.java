package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

abstract class AgentTestSupport {
  record Captured(String uri, Map<String, String> headers, JsonNode body) {}

  record Reply(int status, String type, Flux<String> body) {
    static Reply json(Object body) {
      return new Reply(200, "application/json", Flux.just(Json.write(body)));
    }

    static Reply sse(String body) {
      return new Reply(200, "text/event-stream", Flux.just(body));
    }
  }

  final List<Captured> requests = new CopyOnWriteArrayList<>();
  volatile Function<Captured, Reply> handler;
  DisposableServer server;
  String base;
  WebClient http;

  @BeforeEach
  void setup() {
    handler = this::success;
    server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle(
                (request, response) ->
                    request
                        .receive()
                        .aggregate()
                        .asString(StandardCharsets.UTF_8)
                        .defaultIfEmpty("")
                        .flatMap(
                            body -> {
                              Map<String, String> headers = new LinkedHashMap<>();
                              request
                                  .requestHeaders()
                                  .forEach(
                                      e -> headers.put(e.getKey().toLowerCase(), e.getValue()));
                              Captured captured =
                                  new Captured(request.uri(), headers, Json.parse(body));
                              requests.add(captured);
                              Reply reply = handler.apply(captured);
                              response.status(reply.status).header("Content-Type", reply.type);
                              return response
                                  .sendGroups(
                                      reply.body.map(
                                          s ->
                                              Mono.just(
                                                  Unpooled.copiedBuffer(
                                                      s, StandardCharsets.UTF_8))))
                                  .then();
                            }))
            .bindNow();
    base = "http://127.0.0.1:" + server.port();
    http = WebClient.builder().build();
  }

  @AfterEach
  void teardown() {
    if (server != null) {
      server.disposeNow();
    }
  }

  AgentSettings settings() {
    return settings(Map.of());
  }

  AgentSettings settings(Map<String, String> overrides) {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("RAGFLOW_URL", base + "/api/v1/retrieval")
            .withProperty("RAGFLOW_DATASET_ID", "travel-kb")
            .withProperty("RAGFLOW_API_TOKEN", "search-secret")
            .withProperty("CLASSIFIER_URL", base + "/classifier")
            .withProperty("CLASSIFIER_API_KEY", "classifier-secret");
    overrides.forEach(env::setProperty);
    return new AgentSettings(env);
  }

  WorkflowEvents events(AgentSettings settings) {
    return new WorkflowEvents(
        new TravelAgent(settings, new ExternalServices(http, settings), http), settings);
  }

  Map<String, Object> model(String type) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", type);
    result.put("provider", "openai-compatible");
    result.put("endpoint", base + "/" + type + "/chat/completions?version=1");
    result.put("model_name", "test-model");
    result.put("temperature", "0.1");
    result.put("auth_token", "model-secret");
    return result;
  }

  Map<String, Object> input() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("query", "介绍这个展品");
    raw.put("model_config", List.of(model("text"), model("text_vision")));
    return raw;
  }

  JsonNode payload(Map<String, Object> raw) {
    return Json.MAPPER.valueToTree(Map.of("inputs", raw));
  }

  Inputs.ModelConfig config(Map<String, Object> model) {
    return Inputs.parse(Json.MAPPER.valueToTree(Map.of("model_config", List.of(model))))
        .models()
        .getFirst();
  }

  List<JsonNode> run(Map<String, Object> input) {
    return run(input, settings());
  }

  List<JsonNode> run(Map<String, Object> input, AgentSettings settings) {
    return frames(
        events(settings).stream(payload(input)).collectList().block(Duration.ofSeconds(5)));
  }

  static List<JsonNode> frames(List<String> frames) {
    List<JsonNode> result = new ArrayList<>();
    for (String frame : frames) {
      for (String part : frame.split("\n\n")) {
        int start = part.indexOf("data: ");
        if (start >= 0) {
          result.add(Json.parse(part.substring(start + 6)));
        }
      }
    }
    return result;
  }

  static JsonNode result(List<JsonNode> events) {
    JsonNode end =
        events.stream()
            .filter(e -> e.path("event").asText().equals("workflow_finished"))
            .findFirst()
            .orElseThrow();
    return Json.parse(end.path("data").path("outputs").path("responseContent").asText());
  }

  static List<JsonNode> messages(List<JsonNode> events) {
    return events.stream()
        .filter(e -> e.path("event").asText().equals("message"))
        .map(e -> e.get("data"))
        .toList();
  }

  static String delta(String text, String reasoning) {
    return "data: "
        + Json.write(
            Map.of(
                "choices",
                List.of(
                    Map.of(
                        "index",
                        0,
                        "delta",
                        Map.of("content", text, "reasoning_content", reasoning)))))
        + "\n\n";
  }

  static final String DONE = "data: [DONE]\n\n";

  Reply success(Captured request) {
    if (request.uri.startsWith("/api/v1/retrieval")) {
      return Reply.json(
          Map.of("code", 0, "data", Map.of("chunks",
              List.of(Map.of("content", "秦代陶俑", "document_keyword", "陶俑", "similarity", .88)))));
    }
    if (request.uri.startsWith("/classifier")) {
      return classification("70%");
    }
    return Reply.sse(delta("", "思考") + delta("这是", "") + delta("秦代陶俑。", "") + DONE);
  }

  static Reply classification(Object confidence) {
    return Reply.json(
        Map.of(
            "code",
            200,
            "data",
            Map.of("top1_result", Map.of("class_name", "兵马俑", "confidence", confidence))));
  }
}
