package com.heilos.travel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;

/** Opt-in test against the real local AME adapter; no remote model or chat generation. */
@EnabledIfEnvironmentVariable(named = "AME_TEST_URL", matches = "http://127\\.0\\.0\\.1:.*")
class AmeRetrievalIntegrationTest {
  @Test
  void existingClientConsumesAmeKnowledgeWithoutProductionSourceChanges() throws Exception {
    var env = new MockEnvironment()
        .withProperty("RAGFLOW_URL", System.getenv("AME_TEST_URL"))
        .withProperty("RAGFLOW_API_TOKEN", System.getenv("AME_BENCH_TOKEN"))
        .withProperty("RAGFLOW_DATASET_ID", "ame-yingmu")
        .withProperty("RAGFLOW_USE_RERANK", "false")
        .withProperty("RAGFLOW_TOP_K", "5")
        .withProperty("RAGFLOW_SIMILARITY_THRESHOLD", "0")
        .withProperty("RAGFLOW_MIN_SIMILARITY", "0")
        .withProperty("RAGFLOW_RETRIES", "0");
    var client = new RagFlowClient(WebClient.builder().build(), new AgentSettings(env));
    var queries = Json.parse(Files.readString(Path.of("tools/ame-benchmark/fixtures/queries.json")));
    for (var q : queries) client.search(q.path("question").asText()).block(Duration.ofSeconds(10));
    var samples = new ArrayList<Map<String, Object>>();
    for (int round = 0; round < 10; round++) {
      for (var q : queries) {
        long start = System.nanoTime();
        var hits = client.search(q.path("question").asText()).block(Duration.ofSeconds(10));
        double elapsed = (System.nanoTime() - start) / 1_000_000.0;
        assertThat(hits).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(hits.getFirst().title()).isEqualTo(q.path("expected_document").asText());
        assertThat(hits).allSatisfy(hit -> {
          assertThat(hit.id()).isNotBlank();
          assertThat(hit.content()).isNotBlank();
          assertThat(Double.isFinite(hit.similarity())).isTrue();
        });
        var row = new LinkedHashMap<String, Object>();
        row.put("round", round);
        row.put("query_id", q.path("id").asInt());
        row.put("elapsed_ms", elapsed);
        row.put("returned_ids", hits.stream().map(ExternalServices.KnowledgeChunk::id).toList());
        samples.add(row);
      }
    }
    var output = Path.of("target/ame-validation/java-client-samples.json");
    Files.createDirectories(output.getParent());
    Files.writeString(output, Json.write(samples));
  }
}
