package io.crewscope.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * A deployment that leaves crewscope.collaboration-realtime.enabled unset must expose no
 * collaboration endpoint at all — the fallback contract for deployments without WebSocket.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = CollaborationWsTestSupport.SpikeApplication.class,
    properties = {
      "spring.main.web-application-type=reactive",
      "spring.profiles.active=" + CollaborationWsTestSupport.FIXTURE_PROFILE,
      "spring.session.timeout=15m",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
          + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
          + "org.springframework.boot.session.data.redis.autoconfigure."
          + "SessionDataRedisAutoConfiguration"
    })
class CollaborationWebSocketDisabledIntegrationTest {

  private static final int REDIS_PORT = 6379;

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
          .withExposedPorts(REDIS_PORT)
          .withCommand("redis-server", "--save", "", "--appendonly", "no")
          .waitingFor(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofMinutes(2));

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.data.redis.url",
        () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT));
  }

  private final WebTestClient client;

  @Autowired
  CollaborationWebSocketDisabledIntegrationTest(@LocalServerPort int port) {
    this.client =
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
  }

  @Test
  void disabledChannelExposesNoEndpoint() {
    EntityExchangeResult<byte[]> login =
        client
            .post()
            .uri("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("username", "alice", "password", "alice-password"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult();
    List<ResponseCookie> cookies =
        login.getResponseCookies().get(CollaborationWsTestSupport.SESSION_COOKIE);
    assertThat(cookies).isNotEmpty();

    client
        .get()
        .uri(CollaborationWsTestSupport.WS_PATH)
        .cookie(CollaborationWsTestSupport.SESSION_COOKIE, cookies.get(cookies.size() - 1).getValue())
        .header("Upgrade", "websocket")
        .header("Connection", "Upgrade")
        .header("Sec-WebSocket-Version", "13")
        .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
        .exchange()
        .expectStatus()
        .isNotFound();
  }
}
