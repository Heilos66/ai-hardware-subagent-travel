package com.travel.agent;

import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/** Spring Boot 入口，装配文旅逛展 Agent 的设置、连接池、WebClient 与核心组件。 */
@SpringBootApplication
public class TravelApplication {
  /** 启动 Spring Boot 应用。 */
  public static void main(String[] args) {
    SpringApplication.run(TravelApplication.class, args);
  }

  /** 从环境变量加载应用设置并输出运行时配置诊断日志。 */
  @Bean
  AgentSettings settings(Environment environment) {
    AgentSettings settings = new AgentSettings(environment);
    new WorkflowDiagnostics(settings).runtimeConfig();
    return settings;
  }

  /** 创建上游 HTTP 连接池，限制最大连接数与空闲超时。 */
  @Bean(destroyMethod = "dispose")
  ConnectionProvider connections() {
    return ConnectionProvider.builder("travel-upstream")
        .maxConnections(100)
        .pendingAcquireTimeout(Duration.ofSeconds(30))
        .maxIdleTime(Duration.ofSeconds(60))
        .build();
  }

  /** 创建禁用重定向的 WebClient，设置内存缓冲区上限。 */
  @Bean
  WebClient upstream(ConnectionProvider pool) {
    return WebClient.builder()
        .clientConnector(
            new ReactorClientHttpConnector(HttpClient.create(pool).followRedirect(false)))
        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
        .build();
  }

  /** 装配分类与检索外部服务客户端。 */
  @Bean
  ExternalServices externalServices(WebClient http, AgentSettings settings) {
    return new ExternalServices(http, settings);
  }

  /** 装配文旅逛展 Agent 主逻辑。 */
  @Bean
  TravelAgent agent(AgentSettings settings, ExternalServices services, WebClient http) {
    return new TravelAgent(settings, services, http);
  }

  /** 装配工作流事件流，负责 SSE 帧编排与心跳。 */
  @Bean
  WorkflowEvents events(TravelAgent agent, AgentSettings settings) {
    return new WorkflowEvents(agent, settings);
  }
}
