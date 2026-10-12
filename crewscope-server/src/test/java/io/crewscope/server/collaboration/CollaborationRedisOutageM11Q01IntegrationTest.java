package io.crewscope.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.server.collaboration.CollaborationWsTestSupport.SubscriptionFixture;
import io.crewscope.server.collaboration.CollaborationWsTestSupport.WsConnection;
import io.crewscope.domain.collaboration.ResourceScope;
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
 * M11-Q01 fault-set face of the collaboration channel: a Redis outage must degrade, never
 * detonate. Presence is lossy by contract (ADR-032), so while Redis is down subscriptions
 * still ack (writes are fire-and-forget), reads degrade to absence (no snapshot, no error
 * frame, no close), and the member revocation sweep still cuts with 4403 — the sweep's
 * index is in-memory and the fake authorizer reads a table, not Redis. Recovery is
 * self-healing: the heartbeat refresh re-lands the presence entries, and a member who
 * re-authenticates after the flush is still denied the swept scope.
 *
 * <p>The outage is injected with stop/start container commands (not {@code
 * GenericContainer.stop()}, which would destroy the container and remap the port the
 * context already pinned). The container binds a fixed host port on purpose: Docker
 * Desktop re-allocates randomly published ports when a stopped container starts again
 * (empirically verified), which would strand the URL this context pinned and make the
 * recovery assertions unpassable no matter how healthy the app is. Sessions are
 * Redis-backed in this fixture, so every login
 * happens before the stop, and post-restart assertions re-login — the flushed session
 * store is part of the scenario, not an accident.
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
      // Bound the command latency so failed writes fail fast instead of hanging the ack
      // path for the default 60s — without this the "ack still arrives" assertions are vacuous.
      "spring.data.redis.timeout=1s",
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
class CollaborationRedisOutageM11Q01IntegrationTest {

  private static final int REDIS_PORT = 6379;
  private static final Duration REDIS_TIMEOUT = Duration.ofSeconds(5);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final UUID WORK_ITEM = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

  /** Must mirror the environment property above: the tests read the keys the handler writes. */
  private final CollaborationPresenceKeyspace keyspace =
      new CollaborationPresenceKeyspace("signal");

  /**
 * Fixed host port for the Redis container. Docker Desktop re-allocates randomly published
 * ports across a stop/start cycle (verified: 59300 became 59301), so a random binding
 * would break the recovery half of every scenario here. Claimed once per JVM below; the
 * class is {@link ExecutionMode#SAME_THREAD} and Maven runs single-class at a time, so no
 * sibling claims it first.
 */
  private static final int REDIS_HOST_PORT = findFreePort();

  @Container
  private static final GenericContainer<?> REDIS = createRedis();

  private static GenericContainer<?> createRedis() {
    GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(REDIS_PORT)
            .withCommand("redis-server", "--save", "", "--appendonly", "no")
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(2));
    redis.setPortBindings(List.of(REDIS_HOST_PORT + ":" + REDIS_PORT));
    return redis;
  }

  private static int findFreePort() {
    try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
      socket.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
      return socket.getLocalPort();
    } catch (java.io.IOException noPort) {
      throw new IllegalStateException("no free host port for the Redis container", noPort);
    }
  }

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS_HOST_PORT);
  }

  private final WebTestClient client;
  private final int port;

  @Autowired ReactiveStringRedisTemplate redisTemplate;

  CollaborationRedisOutageM11Q01IntegrationTest(@LocalServerPort int port) {
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
    // Never leave a stopped container behind for the next scenario in this class.
    if (!REDIS.isRunning()) {
      startRedis();
    }
  }

  @Test
  void presenceWriteFailureDuringOutageStillAcksAndKeepsTheConnectionAlive() throws Exception {
    String cookie = login();
    try (WsConnection ws = WsConnection.open(port, cookie)) {
      awaitWelcomeFrame(ws);
      stopRedis();

      ws.sendText(subscribeFrame(itemScope(CollaborationWsTestSupport.ALICE_TEAM_1)));
      // Fire-and-forget presence: the ack must not wait for a write that can never land.
      assertThat(nextNonPingFrame(ws, Duration.ofSeconds(10)))
          .containsEntry("type", "subscribed");

      // No snapshot follows (its read failed), no error frame surfaces, and the heartbeat
      // keeps ticking — the connection was not punished for Redis being down.
      expectOnlyPings(ws, Duration.ofMillis(1500));
      awaitPing(ws, Duration.ofSeconds(5));

      startRedis();
      String scopeKey = keyspace.scopeKey(resourceScopeDomain(CollaborationWsTestSupport.ALICE_TEAM_1));
      // Self-healing: the periodic heartbeat refresh re-lands the presence ZSET once Redis
      // answers again.
      awaitUntil(Duration.ofSeconds(15), () -> zsetHasAnyMember(scopeKey));
    }
  }

  @Test
  void presenceReadsDuringOutageDegradeToAbsenceNotErrorFrames() throws Exception {
    String cookie = login();
    try (WsConnection ws = WsConnection.open(port, cookie)) {
      awaitWelcomeFrame(ws);
      stopRedis();

      // The read path fails during the outage: presence is simply absent — no snapshot
      // frame, no protocol error, no close. Absence is the honest degradation.
      ws.sendText(subscribeFrame(itemScope(CollaborationWsTestSupport.ALICE_TEAM_1)));
      assertThat(nextNonPingFrame(ws, Duration.ofSeconds(10)))
          .containsEntry("type", "subscribed");
      expectOnlyPings(ws, Duration.ofMillis(1500));
      awaitPing(ws, Duration.ofSeconds(5));
    }
  }

  @Test
  void revocationStillCuts4403DuringOutageAndPresenceNeverRebuildsAfterRecovery()
      throws Exception {
    String cookie = login();
    Map<String, Object> scope = itemScope(CollaborationWsTestSupport.ALICE_TEAM_1);
    try (WsConnection first = WsConnection.open(port, cookie)) {
      String connectionId = awaitWelcomeFrame(first);
      wsSubscribeExpectingAck(first, scope);
      consumeSnapshot(first);
      String scopeKey = keyspace.scopeKey(resourceScopeDomain(CollaborationWsTestSupport.ALICE_TEAM_1));
      awaitUntil(
          Duration.ofSeconds(5),
          () -> redisTemplate.opsForZSet().score(scopeKey, connectionId).block(REDIS_TIMEOUT)
                  != null);

      stopRedis();
      // The sweep index is in-memory and the authorizer reads a table, not Redis: the cut
      // does not wait for the store to come back.
      SubscriptionFixture.revokeTeam(
          CollaborationWsTestSupport.ALICE_ACCOUNT_ID, CollaborationWsTestSupport.ALICE_TEAM_1);
      assertThat(first.awaitCloseCode(Duration.ofSeconds(10))).isEqualTo(4403);
    }

    startRedis();
    // The flush took the sessions with it; re-login is the honest post-recovery path and
    // the denial must survive it — the membership fact, not the Redis state, is the grant.
    String freshCookie = login();
    try (WsConnection second = WsConnection.open(port, freshCookie)) {
      awaitWelcomeFrame(second);
      second.sendText(subscribeFrame(scope));
      assertThat(nextNonPingFrame(second, Duration.ofSeconds(5)))
          .containsEntry("type", "error")
          .containsEntry("code", "forbidden_scope");
      String scopeKey = keyspace.scopeKey(resourceScopeDomain(CollaborationWsTestSupport.ALICE_TEAM_1));
      awaitUntil(
          Duration.ofSeconds(5),
          () -> !zsetHasAnyMember(scopeKey));
    }
  }

  // ------------------------------------------------------------------ outage injection

  /** Stops the container without destroying it — the pinned host port survives the stop. */
  private static void stopRedis() {
    REDIS.getDockerClient()
        .stopContainerCmd(REDIS.getContainerId())
        .withTimeout(10)
        .exec();
  }

  private void startRedis() {
    REDIS.getDockerClient()
        .startContainerCmd(REDIS.getContainerId())
        .exec();
    // The honest recovery signal is the app's own connection answering again, not the
    // container merely reporting running — Lettuce reconnects on its own schedule.
    awaitUntil(
        Duration.ofSeconds(20),
        () -> {
          try {
            redisTemplate.opsForValue().get("m11-q01-recovery-probe").block(REDIS_TIMEOUT);
            return true;
          } catch (RuntimeException stillReconnecting) {
            return false;
          }
        });
  }

  /** True when the ZSET holds at least one member — presence exists again. */
  private boolean zsetHasAnyMember(String scopeKey) {
    try {
      Long size = redisTemplate.opsForZSet().size(scopeKey).block(REDIS_TIMEOUT);
      return size != null && size > 0;
    } catch (RuntimeException stillDown) {
      return false;
    }
  }

  // ------------------------------------------------------------------ fixtures

  private static ResourceScope resourceScopeDomain(UUID teamId) {
    return new ResourceScope(
        new OrganizationId(CollaborationWsTestSupport.ORGANIZATION_ID),
        new TeamId(teamId),
        io.crewscope.domain.collaboration.CollaborationResourceType.WORK_ITEM,
        WORK_ITEM);
  }

  private static Map<String, Object> itemScope(UUID teamId) {
    Map<String, Object> scope = new HashMap<>();
    scope.put("organization", CollaborationWsTestSupport.ORGANIZATION_ID.toString());
    scope.put("team", teamId.toString());
    scope.put("resourceType", "work_item");
    scope.put("resourceId", WORK_ITEM.toString());
    return scope;
  }

  private static String subscribeFrame(Map<String, Object> scope) throws Exception {
    return "{\"type\":\"subscribe\",\"scope\":" + JSON.writeValueAsString(scope) + "}";
  }

  private static String awaitWelcomeFrame(WsConnection ws) throws Exception {
    Map<String, Object> welcome = ws.nextFrameJson(Duration.ofSeconds(10));
    assertThat(welcome).containsEntry("type", "welcome");
    return String.valueOf(welcome.get("connectionId"));
  }

  private static void wsSubscribeExpectingAck(WsConnection ws, Map<String, Object> scope)
      throws Exception {
    ws.sendText(subscribeFrame(scope));
    Map<String, Object> frame;
    do {
      frame = ws.nextFrameJson(Duration.ofSeconds(10));
    } while ("ping".equals(frame.get("type"))
        || "welcome".equals(frame.get("type"))
        || "presence_delta".equals(frame.get("type"))
        || "typing".equals(frame.get("type")));
    assertThat(frame).containsEntry("type", "subscribed");
  }

  /** Consumes this connection's own subscription snapshot so later reads are unambiguous. */
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

  /** Fails when any non-ping frame arrives within the quiet window. */
  private static void expectOnlyPings(WsConnection ws, Duration quiet) {
    long deadline = System.nanoTime() + quiet.toNanos();
    while (System.nanoTime() < deadline) {
      String frame;
      try {
        frame = ws.nextFrame(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
      } catch (IllegalStateException quietWindowElapsed) {
        return;
      }
      assertThat(frame).as("unexpected frame during quiet window").contains("\"ping\"");
    }
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
