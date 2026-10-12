package io.crewscope.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.server.collaboration.CollaborationWsTestSupport.SubscriptionFixture;
import io.crewscope.server.collaboration.CollaborationWsTestSupport.WsConnection;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
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
 * M11-Q01 attack-set face of the collaboration channel: revocation residue. A member cut
 * with 4403 must not come back through the same session — reconnecting and resubscribing
 * the swept scope answers forbidden_scope again, a handle stolen from the cut connection
 * routes nowhere, and the Redis presence is never rebuilt under either connection id.
 * The channel itself stays reconnectable (a 4403 is not a refusal of the endpoint), which
 * is exactly why the denial has to hold on every retry, not just the first cut.
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
      "crewscope.collaboration-realtime.inbound-idle-timeout=30s",
      "crewscope.collaboration-realtime.max-inbound-signals-per-second=5",
      "crewscope.collaboration-realtime.revalidation-interval=1s",
      "crewscope.collaboration-realtime.presence-sweep-interval=60s",
      "crewscope.collaboration-realtime.environment=signal"
    })
class CollaborationRevocationReconnectM11Q01IntegrationTest {

  private static final int REDIS_PORT = 6379;
  private static final Duration REDIS_TIMEOUT = Duration.ofSeconds(5);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final UUID WORK_ITEM = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
  private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000f2");

  /** Must mirror the environment property above: the tests read the keys the handler writes. */
  private final CollaborationPresenceKeyspace keyspace =
      new CollaborationPresenceKeyspace("signal");

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

  @Autowired ReactiveStringRedisTemplate redisTemplate;

  CollaborationRevocationReconnectM11Q01IntegrationTest(@LocalServerPort int port) {
    this.client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    this.port = port;
  }

  /** Every scenario mutates the fixture's static team table — each test resets it. */
  @BeforeEach
  void resetMembershipTable() {
    SubscriptionFixture.resetTeams();
  }

  @AfterEach
  void restoreMembershipTable() {
    SubscriptionFixture.resetTeams();
  }

  @Test
  void aCutMemberReconnectingWithTheSameSessionIsDeniedAgainAndPresenceIsNotRebuilt()
      throws Exception {
    String cookie = login();
    String firstId;
    String handle;
    try (WsConnection first = WsConnection.open(port, cookie)) {
      firstId = awaitWelcomeFrame(first);
      // Age out any stale revalidation verdict from an earlier scenario (singleton cache,
      // 1s TTL in this class) before the one subscription this scenario needs to succeed.
      awaitPing(first, Duration.ofSeconds(5));
      awaitPing(first, Duration.ofSeconds(5));
      handle =
          String.valueOf(
              subscribeExpectingAck(first, teamScope(CollaborationWsTestSupport.ALICE_TEAM_1))
                  .get("subscriptionId"));

      SubscriptionFixture.revokeTeam(
          CollaborationWsTestSupport.ALICE_ACCOUNT_ID, CollaborationWsTestSupport.ALICE_TEAM_1);
      assertThat(first.awaitCloseCode(Duration.ofSeconds(10))).isEqualTo(4403);
    }

    // The member reconnects with the very same session cookie — the session itself is not
    // the grant, the membership fact is, and it just changed.
    try (WsConnection second = WsConnection.open(port, cookie)) {
      String secondId = awaitWelcomeFrame(second);

      second.sendText(
          subscribeFrame(teamScope(CollaborationWsTestSupport.ALICE_TEAM_1)));
      assertThat(nextNonPingFrame(second, Duration.ofSeconds(5)))
          .containsEntry("type", "error")
          .containsEntry("code", "forbidden_scope");

      // A handle stolen from the cut connection routes nowhere on the new connection.
      second.sendText(typingFrame(handle, "started"));
      assertThat(nextNonPingFrame(second, Duration.ofSeconds(5)))
          .containsEntry("type", "error")
          .containsEntry("code", "unknown_subscription");

      // The endpoint stays reachable (the member keeps another team), the denial is the
      // membership's, not the socket's.
      awaitPing(second, Duration.ofSeconds(5));

      String scopeKey =
          keyspace.scopeKey(
              new TeamScope(
                  new OrganizationId(CollaborationWsTestSupport.ORGANIZATION_ID),
                  new TeamId(CollaborationWsTestSupport.ALICE_TEAM_1)));
      awaitUntil(
          Duration.ofSeconds(5),
          () ->
              redisTemplate.opsForZSet().score(scopeKey, firstId).block(REDIS_TIMEOUT) == null
                  && redisTemplate.opsForZSet().score(scopeKey, secondId).block(REDIS_TIMEOUT)
                      == null
                  && Boolean.FALSE.equals(
                      redisTemplate.hasKey(keyspace.connectionKey(firstId)).block(REDIS_TIMEOUT))
                  && Boolean.FALSE.equals(
                      redisTemplate
                          .hasKey(keyspace.connectionKey(secondId))
                          .block(REDIS_TIMEOUT)));
    }
  }

  @Test
  void aCutMemberCannotReEnterPresenceThroughAnyScopeGranularity() throws Exception {
    String cookie = login();
    try (WsConnection first = WsConnection.open(port, cookie)) {
      awaitWelcomeFrame(first);
      awaitPing(first, Duration.ofSeconds(5));
      awaitPing(first, Duration.ofSeconds(5));
      subscribeExpectingAck(first, itemScope(CollaborationWsTestSupport.ALICE_TEAM_1));
      consumeSnapshot(first);

      SubscriptionFixture.revokeTeam(
          CollaborationWsTestSupport.ALICE_ACCOUNT_ID, CollaborationWsTestSupport.ALICE_TEAM_1);
      assertThat(first.awaitCloseCode(Duration.ofSeconds(10))).isEqualTo(4403);
    }

    try (WsConnection second = WsConnection.open(port, cookie)) {
      awaitWelcomeFrame(second);
      for (Map<String, Object> scope :
          List.of(
              teamScope(CollaborationWsTestSupport.ALICE_TEAM_1),
              itemScope(CollaborationWsTestSupport.ALICE_TEAM_1),
              projectScope(CollaborationWsTestSupport.ALICE_TEAM_1))) {
        second.sendText(subscribeFrame(scope));
        assertThat(nextNonPingFrame(second, Duration.ofSeconds(5)))
            .containsEntry("type", "error")
            .containsEntry("code", "forbidden_scope");
      }
    }
  }

  // ------------------------------------------------------------------ fixtures

  private static Map<String, Object> teamScope(UUID teamId) {
    Map<String, Object> scope = new HashMap<>();
    scope.put("organization", CollaborationWsTestSupport.ORGANIZATION_ID.toString());
    scope.put("team", teamId.toString());
    return scope;
  }

  private static Map<String, Object> itemScope(UUID teamId) {
    Map<String, Object> scope = teamScope(teamId);
    scope.put("resourceType", "work_item");
    scope.put("resourceId", WORK_ITEM.toString());
    return scope;
  }

  private static Map<String, Object> projectScope(UUID teamId) {
    Map<String, Object> scope = teamScope(teamId);
    scope.put("resourceType", "work_project");
    scope.put("resourceId", PROJECT.toString());
    return scope;
  }

  private static String subscribeFrame(Map<String, Object> scope) throws Exception {
    return "{\"type\":\"subscribe\",\"scope\":" + JSON.writeValueAsString(scope) + "}";
  }

  private static String typingFrame(String subscriptionId, String state) {
    return "{\"type\":\"typing\",\"subscriptionId\":\"" + subscriptionId
        + "\",\"state\":\"" + state + "\"}";
  }

  private static String awaitWelcomeFrame(WsConnection ws) throws Exception {
    Map<String, Object> welcome = ws.nextFrameJson(Duration.ofSeconds(10));
    assertThat(welcome).containsEntry("type", "welcome");
    return String.valueOf(welcome.get("connectionId"));
  }

  /** Sends the subscribe and waits for the ack, skipping protocol frames that may interleave. */
  private static Map<String, Object> subscribeExpectingAck(
      WsConnection ws, Map<String, Object> scope) throws Exception {
    ws.sendText(subscribeFrame(scope));
    Map<String, Object> frame;
    do {
      frame = ws.nextFrameJson(Duration.ofSeconds(10));
    } while ("ping".equals(frame.get("type"))
        || "welcome".equals(frame.get("type"))
        || "presence_snapshot".equals(frame.get("type"))
        || "presence_delta".equals(frame.get("type"))
        || "typing".equals(frame.get("type")));
    assertThat(frame).containsEntry("type", "subscribed");
    return frame;
  }

  /** Consumes this connection's own subscription snapshot so later assertions read clearly. */
  private static void consumeSnapshot(WsConnection ws) throws Exception {
    assertThat(nextNonPingFrame(ws, Duration.ofSeconds(5)))
        .containsEntry("type", "presence_snapshot");
  }

  private static Map<String, Object> nextNonPingFrame(WsConnection ws, Duration timeout)
      throws Exception {
    Map<String, Object> frame;
    do {
      frame = ws.nextFrameJson(timeout);
    } while ("ping".equals(frame.get("type")));
    return frame;
  }

  /** Reads the next heartbeat ping — one tick of the server's clock for boundary waits. */
  private static void awaitPing(WsConnection ws, Duration timeout) throws Exception {
    Map<String, Object> frame;
    do {
      frame = ws.nextFrameJson(timeout);
    } while (!"ping".equals(frame.get("type")));
  }

  private static void awaitUntil(Duration timeout, BooleanSupplier condition) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while awaiting condition", interrupted);
      }
    }
    throw new IllegalStateException("condition not met within " + timeout);
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
