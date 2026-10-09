package io.crewscope.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;

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
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> FRAME_TYPE = new TypeReference<>() {};

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

  /** One real reactor-netty WebSocket client connection with frame and close-code accessors. */
  private static final class WsConnection implements AutoCloseable {

    private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
    private final Sinks.Many<String> toSend = Sinks.many().unicast().onBackpressureBuffer();
    private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
    private final Disposable connection;

    /**
     * The handle lambda captures this before the constructor finishes, but only the three
     * already-initialized sinks above — the connection disposable itself is never touched
     * inside it, so the escape is safe.
     */
    private WsConnection(int port, String sessionCookieValue) {
      this.connection =
          HttpClient.create()
              .headers(
                  headers ->
                      headers.set(
                          "Cookie",
                          CollaborationWsTestSupport.SESSION_COOKIE + "=" + sessionCookieValue))
              .websocket()
              .uri("ws://127.0.0.1:" + port + CollaborationWsTestSupport.WS_PATH)
              .handle(
                  (inbound, outbound) -> {
                    Mono<Void> receiving =
                        inbound
                            .receiveFrames()
                            .doOnNext(
                                frame -> {
                                  if (frame instanceof TextWebSocketFrame text) {
                                    frames.add(text.text());
                                  }
                                })
                            .then();
                    Mono<Void> closing =
                        inbound
                            .receiveCloseStatus()
                            .doOnNext(status -> closeCode.complete(status.code()))
                            .then()
                            .onErrorResume(
                                error -> {
                                  closeCode.complete(-1);
                                  return Mono.empty();
                                });
                    Mono<Void> sending =
                        outbound
                            .sendString(toSend.asFlux(), StandardCharsets.UTF_8)
                            .then();
                    return sending.and(receiving).and(closing);
                  })
              .subscribe();
    }

    static WsConnection open(int port, String sessionCookieValue) {
      return new WsConnection(port, sessionCookieValue);
    }

    Map<String, Object> nextFrameJson(Duration timeout) throws Exception {
      String frame = nextFrame(timeout);
      return JSON.readValue(frame, FRAME_TYPE);
    }

    String nextFrame(Duration timeout) {
      String frame;
      try {
        frame = frames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for a frame");
      }
      if (frame == null) {
        throw new IllegalStateException("no frame arrived within " + timeout);
      }
      return frame;
    }

    void sendText(String frame) {
      toSend.tryEmitNext(frame);
    }

    int awaitCloseCode(Duration timeout) throws Exception {
      try {
        return closeCode.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException timeoutException) {
        throw new IllegalStateException("connection not closed within " + timeout);
      }
    }

    @Override
    public void close() {
      connection.dispose();
    }
  }
}
