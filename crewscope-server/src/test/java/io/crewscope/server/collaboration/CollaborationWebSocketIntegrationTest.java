package io.crewscope.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import io.crewscope.server.collaboration.CollaborationWsTestSupport.WsConnection;
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
 * I01a acceptance over the real server port: unauthenticated and cross-origin upgrades are
 * refused by the existing chain, an authenticated handshake reuses the browser session, the
 * ADR-032 heartbeat and idle constants hold with shortened test values, and the per-principal
 * admission cap closes the extra connection with the application close code. The 1013
 * slow-client budget cannot be exercised honestly on loopback (the OS drains the socket faster
 * than any emit burst) — that proof belongs to the I01c real-network run.
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
          + "SessionDataRedisAutoConfiguration",
      "crewscope.collaboration-realtime.enabled=true",
      "crewscope.collaboration-realtime.heartbeat-interval=1s",
      "crewscope.collaboration-realtime.inbound-idle-timeout=3s",
      "crewscope.collaboration-realtime.max-connections-per-principal=2"
    })
class CollaborationWebSocketIntegrationTest {

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
  private final int port;

  @Autowired
  CollaborationWebSocketIntegrationTest(@LocalServerPort int port) {
    this.client =
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    this.port = port;
  }

  @Test
  void unauthenticatedUpgradeIsRejectedWith401() {
    upgradeRequest().exchange()
        .expectStatus()
        .isUnauthorized()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("authentication_required");
  }

  @Test
  void crossOriginUpgradeIsRejectedWith403() {
    String sessionCookie = login();

    upgradeRequest()
        .header("Origin", "http://evil.example")
        .cookie(CollaborationWsTestSupport.SESSION_COOKIE, sessionCookie)
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  void authenticatedHandshakeReusesSessionAndSurvivesPongCycle() throws Exception {
    String sessionCookie = login();

    try (WsConnection ws = WsConnection.open(port, sessionCookie)) {
      Map<String, Object> welcome = ws.nextFrameJson(Duration.ofSeconds(10));
      assertThat(welcome)
          .containsEntry("type", "welcome")
          .containsKey("connectionId")
          .containsEntry("heartbeatIntervalSeconds", 1)
          .containsEntry("inboundIdleTimeoutSeconds", 3);

      assertThat(ws.nextFrameJson(Duration.ofSeconds(10))).containsEntry("type", "ping");
      ws.sendText("{\"type\":\"pong\"}");
      // The connection must stay up across further heartbeat rounds after the pong.
      assertThat(ws.nextFrameJson(Duration.ofSeconds(10))).containsEntry("type", "ping");
      assertThat(ws.nextFrameJson(Duration.ofSeconds(10))).containsEntry("type", "ping");
    }
  }

  @Test
  void silentConnectionIsClosedForInboundIdle() throws Exception {
    String sessionCookie = login();

    try (WsConnection ws = WsConnection.open(port, sessionCookie)) {
      assertThat(ws.nextFrameJson(Duration.ofSeconds(10))).containsEntry("type", "welcome");
      // No inbound at all: the 3s idle timeout (1s check resolution) must close with 1000.
      assertThat(ws.awaitCloseCode(Duration.ofSeconds(20))).isEqualTo(1000);
    }
  }

  @Test
  void thirdConnectionForTheSamePrincipalIsClosedWithTheApplicationCode() throws Exception {
    String sessionCookie = login();

    // Admission is ordered by handshake completion, not client subscribe order — three
    // concurrent subscribes reach the server in arbitrary order, so whichever connection
    // completes its handshake third is the one rejected. Open the connections one at a
    // time and confirm each welcome before opening the next, making the third connection
    // deterministically the rejected one.
    try (WsConnection first = WsConnection.open(port, sessionCookie)) {
      assertThat(first.nextFrameJson(Duration.ofSeconds(10))).containsEntry("type", "welcome");
      try (WsConnection second = WsConnection.open(port, sessionCookie)) {
        assertThat(second.nextFrameJson(Duration.ofSeconds(10))).containsEntry("type", "welcome");
        try (WsConnection third = WsConnection.open(port, sessionCookie)) {
          assertThat(third.awaitCloseCode(Duration.ofSeconds(10))).isEqualTo(4000);
          // The admitted connections keep receiving heartbeats; this connection never
          // answers pongs, so its heartbeat lands before the 3s idle disconnect fires.
          assertThat(first.nextFrameJson(Duration.ofSeconds(10))).containsEntry("type", "ping");
        }
      }
    }
  }

  private WebTestClient.RequestHeadersSpec<?> upgradeRequest() {
    return client
        .get()
        .uri(CollaborationWsTestSupport.WS_PATH)
        .header("Upgrade", "websocket")
        .header("Connection", "Upgrade")
        .header("Sec-WebSocket-Version", "13")
        .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
  }

  private String login() {
    EntityExchangeResult<byte[]> result =
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
        result.getResponseCookies().get(CollaborationWsTestSupport.SESSION_COOKIE);
    assertThat(cookies).isNotEmpty();
    return cookies.get(cookies.size() - 1).getValue();
  }
}
