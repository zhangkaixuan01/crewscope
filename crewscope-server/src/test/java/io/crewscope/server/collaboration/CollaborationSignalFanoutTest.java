package io.crewscope.server.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.domain.collaboration.CollaborationResourceChanged;
import io.crewscope.domain.collaboration.CollaborationResourceScope;
import io.crewscope.domain.collaboration.CollaborationResourceType;
import io.crewscope.domain.collaboration.ResourceScope;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.collaboration.WorkProjectScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.reactive.socket.CloseStatus;
import reactor.core.publisher.Mono;

/**
 * Lifecycle and emission contract of the A01 fanout: presence deltas with correct
 * remaining counts, the anchored typing window, the three-level resource-change
 * expansion labeled with each receiver's own handle, and the two forced-close paths
 * (over-budget 1013, revoked 4403 with full cleanup).
 */
class CollaborationSignalFanoutTest {

  private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");

  private final OrganizationId organizationId = OrganizationId.generate();
  private final TeamId teamId = TeamId.generate();
  private final TeamId otherTeamId = TeamId.generate();
  private final ResourceScope itemScope = new ResourceScope(
      organizationId, teamId, CollaborationResourceType.WORK_ITEM, UUID.randomUUID());
  private final WorkProjectScope projectScope = new WorkProjectScope(
      organizationId, teamId, WorkProjectId.generate());
  private final TeamScope teamScope = new TeamScope(organizationId, teamId);
  private final TeamScope foreignTeamScope = new TeamScope(organizationId, otherTeamId);

  private final MutableClock clock = new MutableClock(T0);
  private final FakeAuthorizer authorizer = new FakeAuthorizer();
  private final CollaborationSubscriptionRegistry subscriptions =
      new CollaborationSubscriptionRegistry(32);
  private final CollaborationPresenceKeyspace keyspace = new CollaborationPresenceKeyspace("test");
  private final CollaborationPresenceStore presence = mock(CollaborationPresenceStore.class);
  private final CollaborationConnectionMetrics metrics = mock(CollaborationConnectionMetrics.class);
  private final CollaborationSignalFanout fanout = new CollaborationSignalFanout(
      subscriptions, keyspace, presence, metrics,
      new CollaborationRevocationRevalidator(authorizer, Duration.ofSeconds(5), clock),
      Duration.ofSeconds(5), clock);

  /**
   * Mockito returns null for unstubbed reactive methods, which would NPE inside the fanout;
   * an empty presence and a no-op cleanup are the neutral defaults every scenario starts from.
   */
  @BeforeEach
  void stubNeutralPresence() {
    when(presence.listPresent(any())).thenReturn(Mono.just(List.of()));
    when(presence.removeConnection(anyString(), anyCollection())).thenReturn(Mono.empty());
  }

  // ------------------------------------------------------------------ presence

  @Test
  void aSubscriptionAnnouncesEnterToOthersAndSnapshotsToTheNewcomer() {
    Client alice = connected("conn-a", "account:alice", "Alice");
    subscribe(alice, itemScope);
    when(presence.listPresent(itemScope)).thenReturn(Mono.just(List.of(
        new CollaborationPresenceStore.PresentPrincipal(
            "account:alice", "Alice", teamId.toString(), "work_item",
            itemScope.resourceId().toString(), 1))));
    alice.received.clear();

    Client bob = connected("conn-b", "account:bob", "Bob");
    subscribe(bob, itemScope);

    assertEquals(1, alice.received.size(), "the existing subscriber sees only the enter");
    JsonNode delta = frame(alice, 0);
    assertEquals("presence_delta", delta.path("type").asText());
    assertEquals("enter", delta.path("action").asText());
    assertEquals("account:bob", delta.path("principalId").asText());
    assertEquals(1, delta.path("connections").asInt());

    assertEquals(1, bob.received.size(), "the newcomer sees only its own snapshot");
    JsonNode snapshot = frame(bob, 0);
    assertEquals("presence_snapshot", snapshot.path("type").asText());
    assertEquals(bob.handleOf(itemScope), snapshot.path("subscriptionId").asText());
    assertEquals("Alice", snapshot.path("present").get(0).path("displayName").asText());
  }

  @Test
  void aTeamScopeSubscriptionAnnouncesEnterWithoutASnapshot() {
    Client alice = connected("conn-a", "account:alice", "Alice");
    subscribe(alice, teamScope);

    Client bob = connected("conn-b", "account:bob", "Bob");
    subscribe(bob, teamScope);

    assertEquals(1, alice.received.size());
    assertEquals("presence_delta", frame(alice, 0).path("type").asText());
    assertTrue(bob.received.isEmpty(),
        "a 300-principal Team snapshot could exceed the frame budget, so none is sent");
    verify(presence, never()).listPresent(any());
  }

  @Test
  void aDisconnectAnnouncesLeaveWithTheRemainingConnectionCount() {
    Client alice = connected("conn-a", "account:alice", "Alice");
    subscribe(alice, itemScope);
    Client bob = connected("conn-b", "account:bob", "Bob");
    subscribe(bob, itemScope);
    // A second connection of the same principal: the leave must still count it.
    Client aliceTab = connected("conn-a2", "account:alice", "Alice");
    subscribe(aliceTab, itemScope);
    bob.received.clear();

    Set<String> dropped = subscriptions.removeAll("conn-a");
    fanout.disconnected("conn-a", dropped);

    assertEquals(1, bob.received.size());
    JsonNode delta = frame(bob, 0);
    assertEquals("leave", delta.path("action").asText());
    assertEquals("account:alice", delta.path("principalId").asText());
    assertEquals(1, delta.path("connections").asInt(),
        "the principal's other connection keeps them present");
  }

  @Test
  void anUnsubscribeAnnouncesLeaveWhileTheConnectionStaysLive() {
    Client alice = connected("conn-a", "account:alice", "Alice");
    subscribe(alice, itemScope);
    Client bob = connected("conn-b", "account:bob", "Bob");
    subscribe(bob, itemScope);
    alice.received.clear();

    String scopeKey = keyspace.scopeKey(itemScope);
    subscriptions.remove("conn-b", bob.handleOf(itemScope));
    fanout.unsubscribed("conn-b", scopeKey);

    assertEquals(1, alice.received.size());
    JsonNode delta = frame(alice, 0);
    assertEquals("leave", delta.path("action").asText());
    assertEquals(0, delta.path("connections").asInt());
    assertTrue(subscriptions.scopeKeysOf("conn-b").isEmpty());
  }

  // ------------------------------------------------------------------ typing

  @Test
  void typingStartsOncePerAnchoredWindowAndStoppedAlwaysForwards() {
    Client alice = connected("conn-a", "account:alice", "Alice");
    subscribe(alice, itemScope);
    Client bob = connected("conn-b", "account:bob", "Bob");
    subscribe(bob, itemScope);
    String scopeKey = keyspace.scopeKey(itemScope);
    bob.received.clear();

    fanout.typing("conn-a", scopeKey, true);
    assertEquals(1, bob.received.size());
    assertEquals("typing", frame(bob, 0).path("type").asText());
    assertEquals("started", frame(bob, 0).path("state").asText());

    bob.received.clear();
    fanout.typing("conn-a", scopeKey, true);
    assertTrue(bob.received.isEmpty(), "duplicates inside the window are dropped");

    clock.now = T0.plusSeconds(5);
    fanout.typing("conn-a", scopeKey, true);
    assertEquals(1, bob.received.size(),
        "once the anchored window fully elapses, the next started forwards");

    bob.received.clear();
    fanout.typing("conn-a", scopeKey, false);
    assertEquals(1, bob.received.size());
    assertEquals("stopped", frame(bob, 0).path("state").asText());

    bob.received.clear();
    fanout.typing("conn-a", scopeKey, true);
    assertEquals(1, bob.received.size(),
        "a stopped clears the anchor, so the immediate restart is not starved");

    assertTrue(alice.received.stream().noneMatch(
        text -> text.contains("\"typing\"")), "the sender never receives its own frame");
  }

  // ------------------------------------------------------------------ resource changes

  @Test
  void aResourceChangeReachesEveryGranularityWithEachReceiverOwnHandle() {
    Client itemClient = connected("conn-item", "account:item", "Item");
    subscribe(itemClient, itemScope);
    Client projectClient = connected("conn-project", "account:project", "Project");
    subscribe(projectClient, projectScope);
    Client teamClient = connected("conn-team", "account:team", "Team");
    subscribe(teamClient, teamScope);
    Client foreignClient = connected("conn-foreign", "account:foreign", "Foreign");
    subscribe(foreignClient, foreignTeamScope);
    List.of(itemClient, projectClient, teamClient, foreignClient).forEach(c -> c.received.clear());

    fanout.resourceChanged(new CollaborationResourceChanged(
        itemScope, 12, Set.of(itemScope, projectScope, teamScope)));

    // Every granularity sees the changed resource's own coordinates — never its
    // subscription scope — so the client dedup rule applies uniformly.
    assertResourceChanged(itemClient, itemClient.handleOf(itemScope),
        "work_item", itemScope.resourceId().toString(), 12);
    assertResourceChanged(projectClient, projectClient.handleOf(projectScope),
        "work_item", itemScope.resourceId().toString(), 12);
    assertResourceChanged(teamClient, teamClient.handleOf(teamScope),
        "work_item", itemScope.resourceId().toString(), 12);
    assertTrue(foreignClient.received.isEmpty(),
        "a foreign team's scope key is a different subscription space");
  }

  @Test
  void aDuplicateStaleVersionStillDeliversAndTheClientDeduplicates() {
    Client itemClient = connected("conn-item", "account:item", "Item");
    subscribe(itemClient, itemScope);
    itemClient.received.clear();
    Set<CollaborationResourceScope> audience = Set.of(itemScope);

    fanout.resourceChanged(new CollaborationResourceChanged(itemScope, 12, audience));
    fanout.resourceChanged(new CollaborationResourceChanged(itemScope, 7, audience));

    assertEquals(2, itemClient.received.size(),
        "out-of-order delivery is the contract; max(version) is the client's rule");
    assertEquals(12, frame(itemClient, 0).path("version").asLong());
    assertEquals(7, frame(itemClient, 1).path("version").asLong());
  }

  @Test
  void aSustainedSinkFailureNeverThrowsBackIntoTheReceipt() {
    when(presence.listPresent(any())).thenReturn(Mono.error(new IllegalStateException("redis")));
    Client alice = connected("conn-a", "account:alice", "Alice");
    subscribe(alice, itemScope);
    assertTrue(alice.received.stream()
        .noneMatch(text -> text.contains("presence_snapshot")),
        "the lossy snapshot read failed and was skipped, not propagated");

    fanout.resourceChanged(new CollaborationResourceChanged(itemScope, 3, Set.of(itemScope)));
    assertEquals(1, alice.received.size());
  }

  // ------------------------------------------------------------------ forced closes

  @Test
  void anOverBudgetReceiverIsClosedWith1013AndTheOthersKeepFlowing() {
    // No consumer is attached to the slow receiver's channel: an attached one drains the
    // buffer synchronously, so over budget can only be observed against a stalled client.
    Client healthy = connected("conn-ok", "account:ok", "Ok");
    subscribe(healthy, itemScope);
    Client slow = connected("conn-slow", "account:slow", "Slow", 1, false);
    subscribe(slow, itemScope);
    healthy.received.clear();

    fanout.resourceChanged(new CollaborationResourceChanged(itemScope, 4, Set.of(itemScope)));

    assertEquals(List.of(CollaborationSignalFanout.CLOSE_SIGNAL_SLOW), slow.closes);
    assertEquals(1, healthy.received.size());
    verify(metrics).recordClosedSlow();
    verify(metrics).recordSignalEmitted(
        CollaborationConnectionMetrics.SignalFrameType.RESOURCE_CHANGED);
  }

  @Test
  void aDeniedProbeClosesWith4403AndCleansTopologyAndPresence() {
    Client alice = connected("conn-a", "account:alice", "Alice");
    subscribe(alice, itemScope);
    Client bob = connected("conn-b", "account:bob", "Bob");
    subscribe(bob, itemScope);
    bob.received.clear();
    alice.received.clear();
    authorizer.denied.add("account:bob");
    // The subscription-time verdicts are still cached as fresh; only a stale cache lets
    // the delivery path probe again, which is where the denial is discovered.
    clock.now = T0.plusSeconds(6);

    fanout.resourceChanged(new CollaborationResourceChanged(itemScope, 8, Set.of(itemScope)));

    // Deliver-then-revalidate: Bob's frame went out first, then the probe cut him.
    assertEquals(1, bob.received.size());
    assertEquals(List.of(CollaborationSignalFanout.CLOSE_REVOKED), bob.closes);
    assertTrue(subscriptions.scopeKeysOf("conn-b").isEmpty(), "the revocation dropped the topology");
    verify(presence).removeConnection(eq("conn-b"), anyCollection());
    verify(metrics).recordClosedRevoked();
    assertTrue(alice.closes.isEmpty(), "the healthy subscriber is untouched");
    // Alice sees the resource change plus the revoked member's leave delta.
    assertEquals(2, alice.received.size());
  }

  @Test
  void aFreshCacheSkipsTheProbe() {
    Client alice = connected("conn-a", "account:alice", "Alice");
    subscribe(alice, itemScope);
    alice.received.clear();

    fanout.heartbeatTouch("conn-a");
    // One probe already came from the snapshot delivery at subscribe time; the heartbeat
    // tick refreshes the cache unconditionally — otherwise a revocation could only ever
    // surface after the TTL lapse, and the ADR's worst-case window would double.
    assertEquals(2, authorizer.probes);
    int afterHeartbeat = authorizer.probes;

    fanout.resourceChanged(new CollaborationResourceChanged(itemScope, 2, Set.of(itemScope)));
    assertEquals(afterHeartbeat, authorizer.probes, "a fresh verdict emits without re-probing");
    assertEquals(1, alice.received.size());
  }

  // ------------------------------------------------------------------ fixtures

  private Client connected(String connectionId, String principalKey, String displayName) {
    return connected(connectionId, principalKey, displayName, 64, true);
  }

  private Client connected(
      String connectionId, String principalKey, String displayName, int bufferLimit,
      boolean consume) {
    Client client = new Client(connectionId, bufferLimit, consume);
    // Stubbed before register(): opening a when() inside a call that Mockito is already
    // intercepting breaks its state machine (UnfinishedStubbing).
    Authentication authentication = mock(Authentication.class);
    when(authentication.getName()).thenReturn(principalKey);
    fanout.register(
        connectionId, authentication, principalKey, displayName,
        client.channel, client.closes::add);
    return client;
  }

  private void subscribe(Client client, CollaborationResourceScope scope) {
    String scopeKey = keyspace.scopeKey(scope);
    var addition = subscriptions.add(client.connectionId, scopeKey);
    String handle = addition instanceof CollaborationSubscriptionRegistry.Added added
        ? added.subscriptionId()
        : ((CollaborationSubscriptionRegistry.AlreadySubscribed) addition).subscriptionId();
    client.handles.put(scopeKey, handle);
    fanout.subscribed(client.connectionId, scopeKey).block(Duration.ofSeconds(5));
  }

  private void assertResourceChanged(
      Client client, String handle, String resourceType, String resourceId, long version) {
    assertEquals(1, client.received.size());
    JsonNode frame = frame(client, 0);
    assertEquals("resource_changed", frame.path("type").asText());
    assertEquals(handle, frame.path("subscriptionId").asText(),
        "the receiver's own handle labels the frame");
    assertEquals(version, frame.path("version").asLong());
    JsonNode scope = frame.path("scope");
    assertEquals(organizationId.value().toString(), scope.path("organization").asText());
    assertEquals(teamId.value().toString(), scope.path("team").asText());
    assertEquals(resourceType, scope.path("resourceType").asText());
    assertEquals(resourceId, scope.path("resourceId").asText());
  }

  private static JsonNode frame(Client client, int index) {
    try {
      return new ObjectMapper().readTree(client.received.get(index));
    } catch (Exception exception) {
      throw new AssertionError("unparseable frame: " + client.received.get(index), exception);
    }
  }

  private static final class Client {
    private final String connectionId;
    private final CollaborationOutboundChannel channel;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<CloseStatus> closes = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, String> handles = new ConcurrentHashMap<>();

    private Client(String connectionId, int bufferLimit, boolean consume) {
      this.connectionId = connectionId;
      this.channel = new CollaborationOutboundChannel(bufferLimit);
      if (consume) {
        this.channel.stream().subscribe(received::add);
      }
    }

    private String handleOf(CollaborationResourceScope scope) {
      return handles.get(new CollaborationPresenceKeyspace("test").scopeKey(scope));
    }
  }

  private static final class FakeAuthorizer implements CollaborationSubscriptionAuthorizer {
    private final Set<String> denied = ConcurrentHashMap.newKeySet();
    private int probes;

    @Override
    public Mono<CollaborationSubscriptionAuthorizer.Decision> authorize(
        Authentication subject, CollaborationResourceScope scope) {
      probes++;
      return Mono.just(denied.contains(subject.getName())
          ? CollaborationSubscriptionAuthorizer.Decision.DENIED
          : CollaborationSubscriptionAuthorizer.Decision.ALLOWED);
    }
  }

  private static final class MutableClock extends java.time.Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
