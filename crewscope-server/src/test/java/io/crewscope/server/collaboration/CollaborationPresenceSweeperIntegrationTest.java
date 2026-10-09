package io.crewscope.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import io.crewscope.domain.collaboration.CollaborationResourceScope;
import io.crewscope.domain.collaboration.CollaborationResourceType;
import io.crewscope.domain.collaboration.ResourceScope;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ScanOptions;

/**
 * The I01b acceptance round: lazy eviction only fires on scopes someone reads, so the sweeper
 * must reach the abandoned ones. Every scope key loses its expired members while live members,
 * connection hashes, and non-presence keys stay untouched.
 */
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
class CollaborationPresenceSweeperIntegrationTest extends AbstractCollaborationPresenceRedisTest {

  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
  private static final UUID OTHER_TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
  private static final UUID WORK_ITEM = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

  private final CollaborationPresenceKeyspace keyspace = new CollaborationPresenceKeyspace("test");
  private CollaborationPresenceSweeper sweeper;

  // Built per test: the harness fields (redis, clock) only exist after @BeforeEach.
  @BeforeEach
  void setUpSweeper() {
    sweeper = new CollaborationPresenceSweeper(redis, keyspace, Duration.ofSeconds(60), clock);
  }

  @Test
  void sweepEvictsExpiredMembersOfScopesNobodyReads() {
    TeamScope abandonedTeam = new TeamScope(new OrganizationId(ORG), new TeamId(OTHER_TEAM));
    ResourceScope abandonedItem =
        new ResourceScope(
            new OrganizationId(ORG),
            new TeamId(TEAM),
            CollaborationResourceType.WORK_ITEM,
            WORK_ITEM);
    // Three scope indexes nobody ever lists: two abandoned scopes with only expired members...
    seedExpiredMember(abandonedTeam, "conn-dead-1");
    seedExpiredMember(abandonedItem, "conn-dead-2");
    // ...and a mixed scope where a live member sits next to an expired one.
    TeamScope mixedScope = new TeamScope(new OrganizationId(ORG), new TeamId(TEAM));
    seedExpiredMember(mixedScope, "conn-dead-3");
    redis
        .opsForZSet()
        .add(keyspace.scopeKey(mixedScope), "conn-live", (double) START.toEpochMilli() + 45_000)
        .block(REDIS_TIMEOUT);
    // A live connection hash plus an unrelated key under the same collaboration prefix.
    redis
        .<String, String>opsForHash()
        .put(keyspace.connectionKey("conn-live"), "principalId", "account:alice")
        .block(REDIS_TIMEOUT);
    redis
        .<String, String>opsForHash()
        .put("crewscope:test:collaboration:v1:other:thing", "untouched", "yes")
        .block(REDIS_TIMEOUT);

    sweeper.sweepOnce().block(REDIS_TIMEOUT);

    assertThat(redis.opsForZSet().size(keyspace.scopeKey(abandonedTeam)).block(REDIS_TIMEOUT))
        .isZero();
    assertThat(redis.opsForZSet().size(keyspace.scopeKey(abandonedItem)).block(REDIS_TIMEOUT))
        .isZero();
    assertThat(
            redis
                .opsForZSet()
                .range(keyspace.scopeKey(mixedScope), Range.<Long>unbounded())
                .collectList()
                .block(REDIS_TIMEOUT))
        .containsExactly("conn-live");
    // The sweep only touches scope indexes: hashes and unrelated keys survive.
    assertThat(redis.hasKey(keyspace.connectionKey("conn-live")).block(REDIS_TIMEOUT)).isTrue();
    assertThat(redis.hasKey("crewscope:test:collaboration:v1:other:thing").block(REDIS_TIMEOUT))
        .isTrue();
  }

  @Test
  void sweepOnAnEmptyKeyspaceIsANoOp() {
    sweeper.sweepOnce().block(REDIS_TIMEOUT);

    assertThat(
            redis
                .scan(
                    ScanOptions.scanOptions().match(keyspace.scopePattern()).build())
                .collectList()
                .block(REDIS_TIMEOUT))
        .isEmpty();
  }

  @Test
  void lifecycleStartIsIdempotentAndStopCancels() {
    assertThat(sweeper.isRunning()).isFalse();

    sweeper.start();
    assertThat(sweeper.isRunning()).isTrue();

    // A second start must not replace (and orphan) the first subscription.
    sweeper.start();
    assertThat(sweeper.isRunning()).isTrue();

    sweeper.stop();
    assertThat(sweeper.isRunning()).isFalse();

    // Stopping twice, or a restart after stop, stays consistent.
    sweeper.stop();
    assertThat(sweeper.isRunning()).isFalse();
    sweeper.start();
    assertThat(sweeper.isRunning()).isTrue();
    sweeper.stop();
    assertThat(sweeper.isRunning()).isFalse();
  }

  private void seedExpiredMember(CollaborationResourceScope scope, String connectionId) {
    redis
        .opsForZSet()
        .add(keyspace.scopeKey(scope), connectionId, (double) START.toEpochMilli() - 1_000)
        .block(REDIS_TIMEOUT);
  }
}
