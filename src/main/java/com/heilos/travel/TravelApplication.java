package com.heilos.travel;

import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@SpringBootApplication
public class TravelApplication {
  public static void main(String[] args) {
    SpringApplication.run(TravelApplication.class, args);
  }

  @Bean
  AgentSettings settings(Environment environment) {
    return new AgentSettings(environment);
  }

  @Bean(destroyMethod = "dispose")
  ConnectionProvider connections() {
    return ConnectionProvider.builder("travel-upstream")
        .maxConnections(100)
        .pendingAcquireTimeout(Duration.ofSeconds(30))
        .maxIdleTime(Duration.ofSeconds(60))
        .build();
  }

  @Bean
  WebClient upstream(ConnectionProvider pool) {
    return WebClient.builder()
        .clientConnector(
            new ReactorClientHttpConnector(HttpClient.create(pool).followRedirect(false)))
        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
        .build();
  }

  @Bean
  ExternalServices externalServices(WebClient http, AgentSettings settings) {
    return new ExternalServices(http, settings);
  }

  @Bean
  TravelAgent agent(AgentSettings settings, ExternalServices services, WebClient http) {
    return new TravelAgent(settings, services, http);
  }

  @Bean
  WorkflowEvents events(TravelAgent agent, AgentSettings settings) {
    return new WorkflowEvents(agent, settings);
  }
}
