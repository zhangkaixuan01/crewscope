package io.crewscope.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.server.collaboration.CollaborationWsTestSupport.WsConnection;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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
 * I01b acceptance over the real server port: the subscribe protocol intersects every scope
 * with authorization (cross-team and cross-org denied and recorded without revealing whether
 * the target exists), the per-connection cap holds, and the Redis presence lifecycle — conn
 * hash with TTL, scope ZSET entries, heartbeat score slide, disconnect teardown — is visible
 * in the actual instance. Authorization here runs the fixture fake (deterministic
 * principal-to-team table); the real policies have their own tests and the product-level
 * attack sets belong to Q01.
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
      "crewscope.collaboration-realtime.inbound-idle-timeout=8s",
      "crewscope.collaboration-realtime.max-subscriptions-per-connection=2",
      "crewscope.collaboration-realtime.presence-ttl=3s",
      "crewscope.collaboration-realtime.presence-sweep-interval=60s",
      "crewscope.collaboration-realtime.environment=itest"
    })
class CollaborationSubscriptionIntegrationTest {

  private static final int REDIS_PORT = 6379;
  private static final Duration REDIS_TIMEOUT = Duration.ofSeconds(5);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-0000-0000-000000000021");
  private static final UUID WORK_ITEM = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

  /** Must mirror the environment property above: the tests read the same keys the handler writes. */
  private final CollaborationPresenceKeyspace keyspace =
      new CollaborationPresenceKeyspace("itest");

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
  ReactiveStringRedisTemplate redisTemplate;

  @Autowired
  CollaborationSubscriptionIntegrationTest(@LocalServerPort int port) {
    this.client =
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    this.port = port;
  }

  @Test
  void subscribeAcksEchoAndWritesPresence() throws Exception {
    String sessionCookie = login();

    try (WsConnection ws = WsConnection.open(port, sessionCookie)) {
      String connectionId = awaitWelcome(ws);
      Map<String, Object> scope = teamScope(CollaborationWsTestSupport.ALICE_TEAM_1);

      Map<String, Object> subscribed = subscribe(ws, scope);

      assertThat(subscribed).containsEntry("type", "subscribed").containsKey("subscriptionId");
      assertThat(subscribed.get("scope")).isEqualTo(scope);
      String subscriptionId = String.valueOf(subscribed.get("subscriptionId"));

      String connKey = keyspace.connectionKey(connectionId);
      String scopeKey = teamScopeKey(CollaborationWsTestSupport.ALICE_TEAM_1);
      awaitUntil(
          Duration.ofSeconds(5),
          () -> Boolean.TRUE.equals(redisTemplate.hasKey(connKey).block(REDIS_TIMEOUT)));
      Map<String, String> hash =
          redisTemplate
              .<String, String>opsForHash()
              .entries(connKey)
              .collectMap(Map.Entry::getKey, Map.Entry::getValue)
              .block(REDIS_TIMEOUT);
      assertThat(hash)
          .containsEntry("principalId", "account:" + CollaborationWsTestSupport.ALICE_ACCOUNT_ID)
          .containsEntry("organizationId", CollaborationWsTestSupport.ORGANIZATION_ID.toString())
          .containsEntry("teamId", CollaborationWsTestSupport.ALICE_TEAM_1.toString())
          .containsEntry("resourceType", "team")
          .containsEntry("resourceId", "all");
      assertThat(
              redisTemplate.opsForZSet().score(scopeKey, connectionId).block(REDIS_TIMEOUT))
          .isNotNull();

      // The same scope again: the original handle comes back, still one ZSET entry.
      assertThat(subscribe(ws, scope)).containsEntry("subscriptionId", subscriptionId);
    }
  }

  @Test
  void crossTeamAndCrossOrgSubscriptionsAreDeniedWithoutExistenceDisclosure() throws Exception {
    String sessionCookie = login();

    try (WsConnection ws = WsConnection.open(port, sessionCookie)) {
      awaitWelcome(ws);

      Map<String, Object> crossTeam = teamScope(CollaborationWsTestSupport.BOB_TEAM);
      Map<String, Object> denied = subscribe(ws, crossTeam);
      assertThat(denied)
          .containsEntry("type", "error")
          .containsEntry("code", "forbidden_scope")
          .containsEntry("scope", crossTeam);

      Map<String, Object> crossOrg = new HashMap<>();
      crossOrg.put("organization", OTHER_ORG.toString());
      crossOrg.put("team", CollaborationWsTestSupport.ALICE_TEAM_1.toString());
      assertThat(subscribe(ws, crossOrg))
          .containsEntry("type", "error")
          .containsEntry("code", "forbidden_scope");

      // A malformed scope is invalid_scope — the only distinguishable denial, because the
      // client failed to name a scope at all rather than being refused one.
      assertThat(subscribe(ws, Map.of("organization", "not-a-uuid", "team", "also-not")))
          .containsEntry("code", "invalid_scope");

      // Nothing was registered by any denied frame.
      assertThat(scopeKeyCount(teamScopeKey(CollaborationWsTestSupport.BOB_TEAM))).isZero();
    }
  }

  @Test
  void thirdDistinctScopeOnOneConnectionTripsThePerConnectionLimit() throws Exception {
    String sessionCookie = login();

    try (WsConnection ws = WsConnection.open(port, sessionCookie)) {
      awaitWelcome(ws);

      assertThat(
              subscribe(ws, teamScope(CollaborationWsTestSupport.ALICE_TEAM_1))
                  .get("type"))
          .isEqualTo("subscribed");
      assertThat(
              subscribe(ws, teamScope(CollaborationWsTestSupport.ALICE_TEAM_2))
                  .get("type"))
          .isEqualTo("subscribed");

      Map<String, Object> workItemScope =
          Map.of(
              "organization", CollaborationWsTestSupport.ORGANIZATION_ID.toString(),
              "team", CollaborationWsTestSupport.ALICE_TEAM_1.toString(),
              "resourceType", "work_item",
              "resourceId", WORK_ITEM.toString());
      assertThat(subscribe(ws, workItemScope))
          .containsEntry("type", "error")
          .containsEntry("code", "subscription_limit");

      // Re-subscribing an existing scope stays idempotent at the limit.
      assertThat(subscribe(ws, teamScope(CollaborationWsTestSupport.ALICE_TEAM_1)))
          .containsEntry("type", "subscribed");
    }
  }

  @Test
  void unsubscribeRemovesTheScopeEntryAndAcksIdempotently() throws Exception {
    String sessionCookie = login();

    try (WsConnection ws = WsConnection.open(port, sessionCookie)) {
      String connectionId = awaitWelcome(ws);
      Map<String, Object> subscribed =
          subscribe(ws, teamScope(CollaborationWsTestSupport.ALICE_TEAM_1));
      String subscriptionId = String.valueOf(subscribed.get("subscriptionId"));
      String scopeKey = teamScopeKey(CollaborationWsTestSupport.ALICE_TEAM_1);
      awaitUntil(
          Duration.ofSeconds(5),
          () ->
              redisTemplate.opsForZSet().score(scopeKey, connectionId).block(REDIS_TIMEOUT)
                  != null);

      ws.sendText("{\"type\":\"unsubscribe\",\"subscriptionId\":\"" + subscriptionId + "\"}");
      assertThat(ws.nextFrameJson(Duration.ofSeconds(5)))
          .containsEntry("type", "unsubscribed")
          .containsEntry("subscriptionId", subscriptionId);
      awaitUntil(
          Duration.ofSeconds(5),
          () ->
              redisTemplate.opsForZSet().score(scopeKey, connectionId).block(REDIS_TIMEOUT)
                  == null);
      // The conn hash survives an unsubscribe; only disconnect removes it.
      assertThat(
              Boolean.TRUE.equals(
                  redisTemplate
                      .hasKey(keyspace.connectionKey(connectionId))
                      .block(REDIS_TIMEOUT)))
          .isTrue();

      // Unknown handle: still an ack, so a lost ack never wedges the client.
      ws.sendText("{\"type\":\"unsubscribe\",\"subscriptionId\":\"" + subscriptionId + "\"}");
      assertThat(ws.nextFrameJson(Duration.ofSeconds(5)))
          .containsEntry("type", "unsubscribed");
    }
  }

  @Test
  void disconnectTearsDownTheConnectionHashAndScopeEntries() throws Exception {
    String sessionCookie = login();

    String connectionId;
    try (WsConnection ws = WsConnection.open(port, sessionCookie)) {
      connectionId = awaitWelcome(ws);
      subscribe(ws, teamScope(CollaborationWsTestSupport.ALICE_TEAM_1));
    }

    String connKey = keyspace.connectionKey(connectionId);
    String scopeKey = teamScopeKey(CollaborationWsTestSupport.ALICE_TEAM_1);
    awaitUntil(
        Duration.ofSeconds(5),
        () ->
            Boolean.FALSE.equals(redisTemplate.hasKey(connKey).block(REDIS_TIMEOUT))
                && redisTemplate.opsForZSet().score(scopeKey, connectionId).block(REDIS_TIMEOUT)
                    == null);
  }

  @Test
  void heartbeatSlidesThePresenceScoreForward() throws Exception {
    String sessionCookie = login();

    try (WsConnection ws = WsConnection.open(port, sessionCookie)) {
      String connectionId = awaitWelcome(ws);
      String scopeKey = teamScopeKey(CollaborationWsTestSupport.ALICE_TEAM_1);
      AtomicReference<Double> first = new AtomicReference<>();

      subscribe(ws, teamScope(CollaborationWsTestSupport.ALICE_TEAM_1));
      awaitUntil(
          Duration.ofSeconds(5),
          () -> {
            first.compareAndSet(
                null, redisTemplate.opsForZSet().score(scopeKey, connectionId).block(REDIS_TIMEOUT));
            return first.get() != null;
          });

      // One heartbeat round refreshes the score to now+TTL; it must strictly increase.
      assertThat(ws.nextFrameJson(Duration.ofSeconds(10))).containsEntry("type", "ping");
      awaitUntil(
          Duration.ofSeconds(5),
          () -> {
            Double score =
                redisTemplate.opsForZSet().score(scopeKey, connectionId).block(REDIS_TIMEOUT);
            return score != null && first.get() != null && score > first.get();
          });
    }
  }

  private String awaitWelcome(WsConnection ws) throws Exception {
    Map<String, Object> welcome = ws.nextFrameJson(Duration.ofSeconds(10));
    assertThat(welcome)
        .containsEntry("type", "welcome")
        .containsEntry("presenceTtlSeconds", 3);
    return String.valueOf(welcome.get("connectionId"));
  }

  private Map<String, Object> subscribe(WsConnection ws, Map<String, Object> scope) throws Exception {
    ws.sendText(
        "{\"type\":\"subscribe\",\"scope\":" + JSON.writeValueAsString(scope) + "}");
    // The ack may trail a heartbeat ping in the outbound stream; skip non-protocol frames.
    Map<String, Object> frame;
    do {
      frame = ws.nextFrameJson(Duration.ofSeconds(10));
    } while ("ping".equals(frame.get("type")) || "welcome".equals(frame.get("type")));
    return frame;
  }

  private static Map<String, Object> teamScope(UUID teamId) {
    Map<String, Object> scope = new HashMap<>();
    scope.put("organization", CollaborationWsTestSupport.ORGANIZATION_ID.toString());
    scope.put("team", teamId.toString());
    return scope;
  }

  private String teamScopeKey(UUID teamId) {
    return keyspace.scopeKey(
        new TeamScope(
            new OrganizationId(CollaborationWsTestSupport.ORGANIZATION_ID),
            new TeamId(teamId)));
  }

  private Long scopeKeyCount(String scopeKey) {
    Long size = redisTemplate.opsForZSet().size(scopeKey).block(REDIS_TIMEOUT);
    return size == null ? 0L : size;
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
