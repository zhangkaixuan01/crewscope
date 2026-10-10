package io.crewscope.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.application.collaboration.CollaborationSignalSink;
import io.crewscope.application.collaboration.CollaborationSignalSink;
import io.crewscope.domain.collaboration.CollaborationResourceChanged;
import io.crewscope.domain.collaboration.CollaborationResourceScope;
import io.crewscope.domain.collaboration.CollaborationResourceType;
import io.crewscope.domain.collaboration.ResourceScope;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.collaboration.WorkProjectScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.server.collaboration.CollaborationWsTestSupport.SubscriptionFixture;
import io.crewscope.server.collaboration.CollaborationWsTestSupport.WsConnection;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * A01 signal acceptance over the real server port: presence snapshots and deltas, the typing
 * window, the three-level resource-change fanout with per-receiver handles, the inbound rate
 * limit, and the revocation revalidation that cuts a live connection with 4403 and cleans the
 * Redis presence. Authorization and display names run the fixture fakes (deterministic
 * tables); the real policies and the product-level attack sets belong to Q01.
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
class CollaborationSignalIntegrationTest {

  private static final int REDIS_PORT = 6379;
  private static final Duration REDIS_TIMEOUT = Duration.ofSeconds(5);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final UUID WORK_ITEM = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
  private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000f2");

  private static final String ALICE_KEY =
      "account:" + CollaborationWsTestSupport.ALICE_ACCOUNT_ID;

  /** Must mirror the environment property above: the tests read the same keys the handler writes. */
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
  @Autowired CollaborationSignalSink signalSink;

  CollaborationSignalIntegrationTest(@LocalServerPort int port) {
    this.client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    this.port = port;
  }

  /** The revocation scenario mutates the fixture's static team table — every test resets it. */
  @BeforeEach
  void resetMembershipTable() {
    SubscriptionFixture.resetTeams();
  }

  @AfterEach
  void restoreMembershipTable() {
    SubscriptionFixture.resetTeams();
  }

  // ------------------------------------------------------------------ presence

  @Test
  void twoConnectionsOnOneResourceScopeSeeSnapshotEnterAndLeaveDeltas() throws Exception {
    String cookie = login();

    try (WsConnection first = WsConnection.open(port, cookie);
        WsConnection second = WsConnection.open(port, cookie)) {
      Map<String, Object> welcome = first.nextFrameJson(Duration.ofSeconds(10));
      assertThat(welcome)
          .containsEntry("type", "welcome")
          .containsEntry("principalId", ALICE_KEY)
          .containsEntry("typingWindowSeconds", 5)
          .containsEntry("maxInboundSignalsPerSecond", 5);
      awaitWelcomeFrame(second);
      Map<String, Object> itemScope = itemScope(CollaborationWsTestSupport.ALICE_TEAM_1);

      Map<String, Object> subscribed = subscribe(first, itemScope);
      Map<String, Object> snapshot = consumeSnapshot(first);
      assertThat(snapshot)
          .containsEntry("subscriptionId", subscribed.get("subscriptionId"));
      List<Map<String, Object>> present = castList(snapshot.get("present"));
      assertThat(present).hasSize(1);
      assertThat(present.get(0))
          .containsEntry("principalId", ALICE_KEY)
          .containsEntry("displayName", "Alice")
          .containsEntry("connections", 1);

      // The second connection joins: the first sees an enter delta counting both connections.
      subscribe(second, itemScope);
      consumeSnapshot(second);
      Map<String, Object> enter = nextNonPingFrame(first, Duration.ofSeconds(5));
      assertThat(enter)
          .containsEntry("type", "presence_delta")
          .containsEntry("action", "enter")
          .containsEntry("displayName", "Alice")
          .containsEntry("connections", 2);

      // The second's teardown announces the leave with the surviving connection count.
      second.close();
      Map<String, Object> leave = nextNonPingFrame(first, Duration.ofSeconds(5));
      assertThat(leave)
          .containsEntry("type", "presence_delta")
          .containsEntry("action", "leave")
          .containsEntry("connections", 1);
    }
  }

  @Test
  void aTeamScopeSubscriptionIsAcknowledgedWithoutASnapshot() throws Exception {
    try (WsConnection ws = WsConnection.open(port, login())) {
      awaitWelcomeFrame(ws);

      subscribe(ws, teamScope(CollaborationWsTestSupport.ALICE_TEAM_1));

      // No snapshot follows the ack — a Team-granularity snapshot could exceed the frame
      // budget, so those subscribers run on deltas only.
      expectNoNonPingFrame(ws, Duration.ofMillis(500));
    }
  }

  // ------------------------------------------------------------------ typing

  @Test
  void typingForwardsOncePerAnchoredWindowStoppedAlwaysAndUnknownHandlesError() throws Exception {
    String cookie = login();

    try (WsConnection first = WsConnection.open(port, cookie);
        WsConnection second = WsConnection.open(port, cookie)) {
      awaitWelcomeFrame(first);
      awaitWelcomeFrame(second);
      Map<String, Object> itemScope = itemScope(CollaborationWsTestSupport.ALICE_TEAM_1);

      String firstHandle = String.valueOf(subscribe(first, itemScope).get("subscriptionId"));
      consumeSnapshot(first);
      subscribe(second, itemScope);
      consumeSnapshot(second);
      // The first connection also saw the second's enter — drain it so the error
      // assertion below reads a frame that truly answers the unknown handle.
      assertThat(nextNonPingFrame(first, Duration.ofSeconds(5)))
          .containsEntry("type", "presence_delta")
          .containsEntry("action", "enter");

      first.sendText(typingFrame(firstHandle, "started"));
      assertThat(nextNonPingFrame(second, Duration.ofSeconds(5)))
          .containsEntry("type", "typing")
          .containsEntry("state", "started")
          .containsEntry("principalId", ALICE_KEY)
          .containsEntry("displayName", "Alice");

      // A duplicate inside the anchored window is dropped without moving the anchor.
      first.sendText(typingFrame(firstHandle, "started"));
      expectNoNonPingFrame(second, Duration.ofMillis(500));

      // Stopped always forwards and clears the anchor...
      first.sendText(typingFrame(firstHandle, "stopped"));
      assertThat(nextNonPingFrame(second, Duration.ofSeconds(5)))
          .containsEntry("type", "typing")
          .containsEntry("state", "stopped");

      // ...so the immediate restart is not starved by the window.
      first.sendText(typingFrame(firstHandle, "started"));
      assertThat(nextNonPingFrame(second, Duration.ofSeconds(5)))
          .containsEntry("type", "typing")
          .containsEntry("state", "started");

      // A handle this connection never held answers unknown_subscription.
      first.sendText(typingFrame(UUID.randomUUID().toString(), "started"));
      assertThat(nextNonPingFrame(first, Duration.ofSeconds(5)))
          .containsEntry("type", "error")
          .containsEntry("code", "unknown_subscription");
    }
  }

  // ------------------------------------------------------------------ resource changes

  @Test
  void aChangeReachesAllThreeGranularitiesEachWithItsOwnHandleAndOutOfOrderToo() throws Exception {
    String aliceCookie = login();
    String bobCookie = login("bob", "bob-password");

    try (WsConnection itemConn = WsConnection.open(port, aliceCookie);
        WsConnection projectConn = WsConnection.open(port, aliceCookie);
        WsConnection teamConn = WsConnection.open(port, aliceCookie);
        WsConnection foreignConn = WsConnection.open(port, bobCookie)) {
      awaitWelcomeFrame(itemConn);
      awaitWelcomeFrame(projectConn);
      awaitWelcomeFrame(teamConn);
      awaitWelcomeFrame(foreignConn);

      String itemHandle =
          String.valueOf(
              subscribe(itemConn, itemScope(CollaborationWsTestSupport.ALICE_TEAM_1))
                  .get("subscriptionId"));
      String projectHandle =
          String.valueOf(
              subscribe(projectConn, projectScope(CollaborationWsTestSupport.ALICE_TEAM_1))
                  .get("subscriptionId"));
      String teamHandle =
          String.valueOf(
              subscribe(teamConn, teamScope(CollaborationWsTestSupport.ALICE_TEAM_1))
                  .get("subscriptionId"));
      subscribe(foreignConn, teamScope(CollaborationWsTestSupport.BOB_TEAM));
      // Drain the resource-granularity snapshots so the change assertion reads the signal.
      consumeSnapshot(itemConn);
      consumeSnapshot(projectConn);

      ResourceScope resource = resourceScope(CollaborationWsTestSupport.ALICE_TEAM_1);
      Set<CollaborationResourceScope> audience =
          Set.of(
              resource,
              projectScopeDomain(CollaborationWsTestSupport.ALICE_TEAM_1),
              teamScopeDomain(CollaborationWsTestSupport.ALICE_TEAM_1));
      signalSink.resourceChanged(new CollaborationResourceChanged(resource, 12, audience));

      assertThat(nextNonPingFrame(itemConn, Duration.ofSeconds(5)))
          .containsEntry("type", "resource_changed")
          .containsEntry("subscriptionId", itemHandle)
          .containsEntry("version", 12)
          .containsEntry("scope", itemScope(CollaborationWsTestSupport.ALICE_TEAM_1));
      assertThat(nextNonPingFrame(projectConn, Duration.ofSeconds(5)))
          .containsEntry("type", "resource_changed")
          .containsEntry("subscriptionId", projectHandle)
          .containsEntry("version", 12);
      assertThat(nextNonPingFrame(teamConn, Duration.ofSeconds(5)))
          .containsEntry("type", "resource_changed")
          .containsEntry("subscriptionId", teamHandle)
          .containsEntry("version", 12);

      // A foreign team's scope key is a different subscription space: zero frames.
      expectNoNonPingFrame(foreignConn, Duration.ofMillis(500));

      // Out-of-order delivery is the contract: a stale version still arrives, and the
      // client's rule is max(version) per (resourceType, resourceId).
      signalSink.resourceChanged(new CollaborationResourceChanged(resource, 7, audience));
      assertThat(nextNonPingFrame(itemConn, Duration.ofSeconds(5)))
          .containsEntry("type", "resource_changed")
          .containsEntry("version", 7);
    }
  }

  // ------------------------------------------------------------------ rate limit

  @Test
  void sustainedInboundAbuseAnswersRateLimitedThenClosesWith1013() throws Exception {
    try (WsConnection ws = WsConnection.open(port, login())) {
      awaitWelcomeFrame(ws);
      String subscribeFrame =
          "{\"type\":\"subscribe\",\"scope\":"
              + JSON.writeValueAsString(teamScope(CollaborationWsTestSupport.ALICE_TEAM_1))
              + "}";

      // Token bucket capacity 5/s: five frames admit (one fresh subscribe plus idempotent
      // re-subscribes), two more answer rate_limited, and the third violation in the window
      // escalates to a 1013 close — twenty frames is more than enough to cross all three.
      for (int i = 0; i < 20; i++) {
        ws.sendText(subscribeFrame);
      }
      assertThat(ws.awaitCloseCode(Duration.ofSeconds(10))).isEqualTo(1013);

      int subscribed = 0;
      int rateLimited = 0;
      String frame;
      while (true) {
        try {
          frame = ws.nextFrame(Duration.ofMillis(100));
        } catch (IllegalStateException drained) {
          break;
        }
        if (frame.contains("\"type\":\"subscribed\"")) {
          subscribed++;
        } else if (frame.contains("\"code\":\"rate_limited\"")) {
          rateLimited++;
        }
      }
      assertThat(subscribed).isGreaterThanOrEqualTo(5);
      assertThat(rateLimited).isGreaterThanOrEqualTo(2);
    }
  }

  // ------------------------------------------------------------------ revocation

  @Test
  void aRevokedTeamCutsTheLiveConnectionWith4403AndCleansPresence() throws Exception {
    try (WsConnection ws = WsConnection.open(port, login())) {
      String connectionId = awaitWelcomeFrame(ws);
      Map<String, Object> itemScope = itemScope(CollaborationWsTestSupport.ALICE_TEAM_1);
      subscribe(ws, itemScope);
      // Drain the snapshot so the close below cannot race a queued frame.
      consumeSnapshot(ws);

      String connKey = keyspace.connectionKey(connectionId);
      String scopeKey = keyspace.scopeKey(resourceScope(CollaborationWsTestSupport.ALICE_TEAM_1));
      awaitUntil(
          Duration.ofSeconds(5),
          () -> Boolean.TRUE.equals(redisTemplate.hasKey(connKey).block(REDIS_TIMEOUT)));

      // The heartbeat tick (1s) plus the revalidation cache (1s) bound the discovery; the
      // acceptance line is 4403 within a few seconds plus a fully cleaned Redis.
      SubscriptionFixture.revokeTeam(
          CollaborationWsTestSupport.ALICE_ACCOUNT_ID, CollaborationWsTestSupport.ALICE_TEAM_1);

      assertThat(ws.awaitCloseCode(Duration.ofSeconds(10))).isEqualTo(4403);
      awaitUntil(
          Duration.ofSeconds(5),
          () ->
              Boolean.FALSE.equals(redisTemplate.hasKey(connKey).block(REDIS_TIMEOUT))
                  && redisTemplate.opsForZSet().score(scopeKey, connectionId)
                          .block(REDIS_TIMEOUT)
                      == null);
    }
  }

  // ------------------------------------------------------------------ subscription cleanup

  @Test
  void unsubscribeAndDisconnectAnnounceLeavesAndCleanTheRedisEntries() throws Exception {
    String cookie = login();

    String firstConnectionId;
    try (WsConnection first = WsConnection.open(port, cookie);
        WsConnection second = WsConnection.open(port, cookie)) {
      firstConnectionId = awaitWelcomeFrame(first);
      String secondConnectionId = awaitWelcomeFrame(second);
      Map<String, Object> itemScope = itemScope(CollaborationWsTestSupport.ALICE_TEAM_1);

      subscribe(first, itemScope);
      consumeSnapshot(first);
      String secondHandle =
          String.valueOf(subscribe(second, itemScope).get("subscriptionId"));
      // The survivor saw the second's enter; drain it so the leave assertion cannot race.
      assertThat(nextNonPingFrame(first, Duration.ofSeconds(5)))
          .containsEntry("type", "presence_delta")
          .containsEntry("action", "enter");
      consumeSnapshot(second);

      // Unsubscribe: the survivor sees the leave while the connection stays live.
      second.sendText("{\"type\":\"unsubscribe\",\"subscriptionId\":\"" + secondHandle + "\"}");
      Map<String, Object> leave = nextNonPingFrame(first, Duration.ofSeconds(5));
      assertThat(leave)
          .containsEntry("type", "presence_delta")
          .containsEntry("action", "leave")
          .containsEntry("connections", 1);
      String scopeKey = keyspace.scopeKey(resourceScope(CollaborationWsTestSupport.ALICE_TEAM_1));
      awaitUntil(
          Duration.ofSeconds(5),
          () ->
              redisTemplate.opsForZSet().score(scopeKey, secondConnectionId)
                      .block(REDIS_TIMEOUT)
                  == null);

      // Disconnect: the remaining entry disappears with the connection.
      first.close();
      awaitUntil(
          Duration.ofSeconds(5),
          () ->
              redisTemplate.opsForZSet().score(scopeKey, firstConnectionId)
                      .block(REDIS_TIMEOUT)
                  == null
                  && Boolean.FALSE.equals(
                      redisTemplate
                          .hasKey(keyspace.connectionKey(firstConnectionId))
                          .block(REDIS_TIMEOUT)));
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

  private static ResourceScope resourceScope(UUID teamId) {
    return new ResourceScope(
        new OrganizationId(CollaborationWsTestSupport.ORGANIZATION_ID),
        new TeamId(teamId),
        CollaborationResourceType.WORK_ITEM,
        WORK_ITEM);
  }

  private static WorkProjectScope projectScopeDomain(UUID teamId) {
    return new WorkProjectScope(
        new OrganizationId(CollaborationWsTestSupport.ORGANIZATION_ID),
        new TeamId(teamId),
        new WorkProjectId(PROJECT));
  }

  private static TeamScope teamScopeDomain(UUID teamId) {
    return new TeamScope(
        new OrganizationId(CollaborationWsTestSupport.ORGANIZATION_ID), new TeamId(teamId));
  }

  private static String typingFrame(String subscriptionId, String state) {
    return "{\"type\":\"typing\",\"subscriptionId\":\"" + subscriptionId
        + "\",\"state\":\"" + state + "\"}";
  }

  /** Reads the welcome frame loosely — alice-only field assertions live in the tests. */
  private String awaitWelcomeFrame(WsConnection ws) throws Exception {
    Map<String, Object> welcome = ws.nextFrameJson(Duration.ofSeconds(10));
    assertThat(welcome).containsEntry("type", "welcome");
    return String.valueOf(welcome.get("connectionId"));
  }

  /**
   * Consumes this connection's own subscription snapshot. The ack returns before the
   * snapshot (an asynchronous Redis read produces it), so the snapshot always trails the
   * subscribe helper — callers that then assert on peer events must drain it first.
   */
  private static Map<String, Object> consumeSnapshot(WsConnection ws) throws Exception {
    Map<String, Object> frame = nextNonPingFrame(ws, Duration.ofSeconds(5));
    assertThat(frame).containsEntry("type", "presence_snapshot");
    return frame;
  }

  private Map<String, Object> subscribe(WsConnection ws, Map<String, Object> scope)
      throws Exception {
    ws.sendText(
        "{\"type\":\"subscribe\",\"scope\":" + JSON.writeValueAsString(scope) + "}");
    // The ack may trail a heartbeat ping; skip non-protocol frames except the snapshot,
    // which callers that need it read explicitly afterwards.
    Map<String, Object> frame;
    do {
      frame = ws.nextFrameJson(Duration.ofSeconds(10));
    } while ("ping".equals(frame.get("type"))
        || "welcome".equals(frame.get("type"))
        || "presence_snapshot".equals(frame.get("type"))
        || "presence_delta".equals(frame.get("type"))
        || "typing".equals(frame.get("type")));
    return frame;
  }

  private static Map<String, Object> nextNonPingFrame(WsConnection ws, Duration timeout)
      throws Exception {
    Map<String, Object> frame;
    do {
      frame = ws.nextFrameJson(timeout);
    } while ("ping".equals(frame.get("type")));
    return frame;
  }

  /** Fails when any non-ping frame arrives within the quiet window. */
  private static void expectNoNonPingFrame(WsConnection ws, Duration quiet) {
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

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> castList(Object raw) {
    return (List<Map<String, Object>>) raw;
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
    return login("alice", "alice-password");
  }

  private String login(String username, String password) {
    EntityExchangeResult<byte[]> result =
        client
            .post()
            .uri("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("username", username, "password", password))
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
