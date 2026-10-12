package com.heilos.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

class RagFlowConfigurationTest extends AgentTestSupport {
  @Test
  void runtimePropertiesResolveMissingConfigurationWithoutChangingCode() {
    var env = new MockEnvironment();
    assertThat(result(run(input(), new AgentSettings(env))).path("message").asText())
        .isEqualTo("RAGFlow地址未配置：RAGFLOW_URL");
    assertThat(requests).isEmpty();
    env.setProperty("RAGFLOW_ENABLED", "false");
    assertThat(result(run(input(), new AgentSettings(env))).path("code").asText()).isEqualTo("0");
    assertThat(requests).hasSize(1);
    assertThat(requests.getFirst().uri()).startsWith("/text/");
    requests.clear();
    env.setProperty("RAGFLOW_ENABLED", "true");
    assertThat(result(run(input(), new AgentSettings(env))).path("code").asText()).isEqualTo("E5004");
    assertThat(requests).isEmpty();
    env.setProperty("RAGFLOW_URL", base + "/api/v1/retrieval");
    env.setProperty("RAGFLOW_DATASET_ID", "travel-kb");
    env.setProperty("RAGFLOW_API_TOKEN", "search-secret");
    assertThat(result(run(input(), new AgentSettings(env))).path("code").asText()).isEqualTo("0");
    assertThat(requests).hasSize(2);
  }

  @Test
  void invalidRetrievalSwitchFailsClearly() {
    assertThatThrownBy(() -> settings(Map.of("RAGFLOW_ENABLED", "invalid-switch-value")))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RAGFLOW_ENABLED")
        .hasMessageNotContaining("invalid-switch-value");
  }

  @ParameterizedTest
  @CsvSource(value = {
    "RAGFLOW_URL||RAGFlow地址未配置：RAGFLOW_URL",
    "RAGFLOW_DATASET_ID||RAGFlow知识库ID未配置：RAGFLOW_DATASET_ID",
    "RAGFLOW_DATASET_ID|invalid/id|RAGFlow知识库ID格式错误：RAGFLOW_DATASET_ID"
  }, delimiter = '|', nullValues = "")
  void configurationFailureIdentifiesExactVariable(String name, String value, String message) {
    var response = result(run(input(), settings(Map.of(name, value == null ? "" : value))));
    assertThat(response.path("code").asText()).isEqualTo("E5004");
    assertThat(response.path("message").asText()).isEqualTo(message);
    assertThat(requests).isEmpty();
  }
}
