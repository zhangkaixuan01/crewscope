package io.crewscope.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import io.crewscope.domain.collaboration.CollaborationResourceType;
import io.crewscope.domain.collaboration.ResourceScope;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.collaboration.WorkProjectScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Real-Redis contract of the ADR-032 presence model: the conn hash carries the TTL, the
 * scope ZSETs carry the fanout set scored by expiry, reads lazily evict, and views
 * deduplicate per principal. Scores are deterministic through the mutable clock.
 */
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
class CollaborationPresenceStoreIntegrationTest extends AbstractCollaborationPresenceRedisTest {

  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
  private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
  private static final UUID WORK_ITEM = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

  private final CollaborationPresenceKeyspace keyspace = new CollaborationPresenceKeyspace("test");
  private CollaborationPresenceStore store;

  // Built per test: the harness fields (redis, clock) only exist after @BeforeEach.
  @BeforeEach
  void setUpStore() {
    store = new CollaborationPresenceStore(redis, keyspace, PRESENCE_TTL, clock);
  }

  private final TeamScope teamScope = new TeamScope(new OrganizationId(ORG), new TeamId(TEAM));
  private final WorkProjectScope projectScope =
      new WorkProjectScope(
          new OrganizationId(ORG), new TeamId(TEAM), new WorkProjectId(PROJECT));
  private final ResourceScope itemScope =
      new ResourceScope(
          new OrganizationId(ORG),
          new TeamId(TEAM),
          CollaborationResourceType.WORK_ITEM,
          WORK_ITEM);

  @Test
  void registerWritesTheAdrHashShapeWithTtlAndTheScopeEntry() {
    store.register("conn-1", "account:alice", itemScope, START).block(REDIS_TIMEOUT);

    Map<String, String> hash =
        redis
            .<String, String>opsForHash()
            .entries(keyspace.connectionKey("conn-1"))
            .collectMap(Map.Entry::getKey, Map.Entry::getValue)
            .block(REDIS_TIMEOUT);
    assertThat(hash)
        .containsEntry("organizationId", ORG.toString())
        .containsEntry("teamId", TEAM.toString())
        .containsEntry("principalId", "account:alice")
        .containsEntry("displayName", "")
        .containsEntry("resourceType", "work_item")
        .containsEntry("resourceId", WORK_ITEM.toString())
        .containsEntry("connectedAt", Long.toString(START.toEpochMilli()))
        .containsEntry("lastSeen", Long.toString(START.toEpochMilli()));
    // getExpire returns a Duration in Spring Data Redis 4.x.
    Duration ttl = redis.getExpire(keyspace.connectionKey("conn-1")).block(REDIS_TIMEOUT);
    assertThat(ttl).isPositive();
    Double score =
        redis
            .opsForZSet()
            .score(keyspace.scopeKey(itemScope), "conn-1")
            .block(REDIS_TIMEOUT);
    // START + 45s: the clock is frozen, so the expiry score is exact.
    assertThat(score).isEqualTo(START.toEpochMilli() + 45_000.0);
  }

  @Test
  void registerOnAScopeChangeRewritesTheHashButKeepsEveryScopeEntry() {
    store.register("conn-1", "account:alice", itemScope, START).block(REDIS_TIMEOUT);
    clock.advance(Duration.ofSeconds(5));

    store.register("conn-1", "account:alice", teamScope, START).block(REDIS_TIMEOUT);

    // The hash mirrors the most recently active subscription (ADR single-scope fields)...
    String resourceType =
        redis
            .<String, String>opsForHash()
            .get(keyspace.connectionKey("conn-1"), "resourceType")
            .block(REDIS_TIMEOUT);
    assertThat(resourceType).isEqualTo("team");
    // ...while the ZSETs hold the complete multi-subscription truth.
    assertThat(
            redis.opsForZSet().score(keyspace.scopeKey(itemScope), "conn-1").block(REDIS_TIMEOUT))
        .isEqualTo(START.toEpochMilli() + 45_000.0);
    assertThat(
            redis.opsForZSet().score(keyspace.scopeKey(teamScope), "conn-1").block(REDIS_TIMEOUT))
        .isEqualTo(START.toEpochMilli() + 5_000 + 45_000.0);
  }

  @Test
  void refreshSlidesTheConnTtlAndEveryActiveScopeScoreForward() {
    store.register("conn-1", "account:alice", itemScope, START).block(REDIS_TIMEOUT);
    store.register("conn-1", "account:alice", teamScope, START).block(REDIS_TIMEOUT);
    clock.advance(Duration.ofSeconds(15));

    store.refresh("conn-1", List.of(keyspace.scopeKey(itemScope), keyspace.scopeKey(teamScope)))
        .block(REDIS_TIMEOUT);

    assertThat(
            redis.opsForZSet().score(keyspace.scopeKey(itemScope), "conn-1").block(REDIS_TIMEOUT))
        .isEqualTo(START.toEpochMilli() + 15_000 + 45_000.0);
    assertThat(
            redis.opsForZSet().score(keyspace.scopeKey(teamScope), "conn-1").block(REDIS_TIMEOUT))
        .isEqualTo(START.toEpochMilli() + 15_000 + 45_000.0);
    Duration ttl = redis.getExpire(keyspace.connectionKey("conn-1")).block(REDIS_TIMEOUT);
    assertThat(ttl).isBetween(Duration.ofSeconds(30), Duration.ofSeconds(45));
  }

  @Test
  void removeConnectionDeletesTheHashAndEveryScopeEntry() {
    store.register("conn-1", "account:alice", itemScope, START).block(REDIS_TIMEOUT);
    store.register("conn-1", "account:alice", teamScope, START).block(REDIS_TIMEOUT);

    store.removeConnection("conn-1", List.of(keyspace.scopeKey(itemScope), keyspace.scopeKey(teamScope)))
        .block(REDIS_TIMEOUT);

    assertThat(redis.hasKey(keyspace.connectionKey("conn-1")).block(REDIS_TIMEOUT)).isFalse();
    assertThat(
            redis.opsForZSet().size(keyspace.scopeKey(itemScope)).block(REDIS_TIMEOUT))
        .isZero();
    assertThat(redis.opsForZSet().size(keyspace.scopeKey(teamScope)).block(REDIS_TIMEOUT))
        .isZero();
  }

  @Test
  void removeScopeDropsOneEntryButKeepsTheConnectionHash() {
    store.register("conn-1", "account:alice", itemScope, START).block(REDIS_TIMEOUT);
    store.register("conn-1", "account:alice", teamScope, START).block(REDIS_TIMEOUT);

    store.removeScope("conn-1", keyspace.scopeKey(itemScope)).block(REDIS_TIMEOUT);

    assertThat(
            redis.opsForZSet().size(keyspace.scopeKey(itemScope)).block(REDIS_TIMEOUT))
        .isZero();
    assertThat(redis.opsForZSet().size(keyspace.scopeKey(teamScope)).block(REDIS_TIMEOUT))
        .isEqualTo(1L);
    assertThat(redis.hasKey(keyspace.connectionKey("conn-1")).block(REDIS_TIMEOUT)).isTrue();
  }

  @Test
  void listPresentDeduplicatesConnectionsByPrincipal() {
    store.register("conn-1", "account:alice", itemScope, "Alice", START).block(REDIS_TIMEOUT);
    store.register("conn-2", "account:alice", itemScope, "Alice", START).block(REDIS_TIMEOUT);
    store.register("conn-3", "account:bob", itemScope, "", START).block(REDIS_TIMEOUT);

    List<CollaborationPresenceStore.PresentPrincipal> present =
        store.listPresent(itemScope).block(REDIS_TIMEOUT);

    assertThat(present)
        .containsExactlyInAnyOrder(
            new CollaborationPresenceStore.PresentPrincipal(
                "account:alice", "Alice", TEAM.toString(), "work_item", WORK_ITEM.toString(), 2),
            new CollaborationPresenceStore.PresentPrincipal(
                "account:bob", "", TEAM.toString(), "work_item", WORK_ITEM.toString(), 1));
  }

  @Test
  void listPresentLazilyEvictsExpiredMembersAndSkipsVanishedHashes() {
    // An orphan ZSET member (its conn hash never existed or already expired).
    redis
        .opsForZSet()
        .add(keyspace.scopeKey(projectScope), "ghost-conn", START.toEpochMilli() - 1_000.0)
        .block(REDIS_TIMEOUT);
    // A live connection whose entry is still valid.
    store.register("conn-live", "account:alice", projectScope, START).block(REDIS_TIMEOUT);

    List<CollaborationPresenceStore.PresentPrincipal> present =
        store.listPresent(projectScope).block(REDIS_TIMEOUT);

    assertThat(present)
        .hasSize(1)
        .first()
        .extracting(CollaborationPresenceStore.PresentPrincipal::principalId)
        .isEqualTo("account:alice");
    // The lazy eviction removed the expired member from the index itself.
    assertThat(
            redis
                .opsForZSet()
                .score(keyspace.scopeKey(projectScope), "ghost-conn")
                .block(REDIS_TIMEOUT))
        .isNull();
  }

  @Test
  void listPresentOnAnUnknownScopeIsEmptyWithoutFailing() {
    assertThat(store.listPresent(teamScope).block(REDIS_TIMEOUT)).isEmpty();
  }
}
