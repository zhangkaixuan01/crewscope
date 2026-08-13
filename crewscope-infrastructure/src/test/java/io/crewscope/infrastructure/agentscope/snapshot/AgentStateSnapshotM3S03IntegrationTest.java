package io.crewscope.infrastructure.agentscope.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.artifact.ArtifactContent;
import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactProducer;
import io.crewscope.application.artifact.ArtifactScope;
import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.artifact.ArtifactWriteRequest;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.infrastructure.agentscope.snapshot.AgentStateSnapshotAdapter.RecoveryResult;
import io.crewscope.infrastructure.agentscope.snapshot.AgentStateSnapshotAdapter.RecoveryTarget;
import io.crewscope.infrastructure.agentscope.snapshot.AgentStateSnapshotAdapter.SkipReason;
import io.crewscope.infrastructure.agentscope.snapshot.AgentStateSnapshotAdapter.SnapshotCandidate;
import io.crewscope.infrastructure.agentscope.snapshot.AgentStateSnapshotAdapter.SnapshotIdentity;
import io.crewscope.infrastructure.agentscope.snapshot.AgentStateSnapshotAdapter.WriteRequest;
import io.crewscope.infrastructure.artifact.FilesystemArtifactStore;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPooled;
import tools.jackson.databind.ObjectMapper;

/** Failure-injection evidence for the M3-S03 AgentState secondary recovery protocol. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class AgentStateSnapshotM3S03IntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-13T02:00:00Z");
    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final PrincipalId PRINCIPAL_ID = PrincipalId.generate();
    private static final SnapshotIdentity IDENTITY = new SnapshotIdentity(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "crewscope-task-agent-v3",
            "crewscope-task-agent-v3",
            "3",
            "crewscope:v1:user:m3-s03",
            "crewscope:v1:session:m3-s03");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(REDIS_PORT)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no")
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    @TempDir Path artifactRoot;

    private ArtifactStore artifactStore;
    private AgentStateSnapshotAdapter adapter;

    @BeforeEach
    void setUp() {
        artifactStore = new FilesystemArtifactStore(
                artifactRoot,
                new ObjectMapper(),
                Clock.fixed(CAPTURED_AT, ZoneOffset.UTC));
        adapter = new AgentStateSnapshotAdapter(artifactStore);
        try (JedisPooled jedis = newClient()) {
            jedis.flushDB();
        }
    }

    @Test
    void restoresTheNewestCommittedSnapshotAfterRedisIsClearedUsingNewClients() {
        SnapshotCandidate first = snapshot(1, state("checkpoint-one"));
        SnapshotCandidate second = snapshot(2, state("checkpoint-two"));

        try (RedisFixture firstProcess = redisStore("m3-s03-clear:")) {
            firstProcess.store().save(
                    IDENTITY.userId(), IDENTITY.sessionId(), "agent_state", state("hot-only"));
        }
        try (JedisPooled jedis = newClient()) {
            jedis.flushDB();
        }

        RecoveryResult result;
        ArtifactStore restartedArtifactStore = new FilesystemArtifactStore(
                artifactRoot,
                new ObjectMapper(),
                Clock.fixed(CAPTURED_AT, ZoneOffset.UTC));
        AgentStateSnapshotAdapter restartedAdapter =
                new AgentStateSnapshotAdapter(restartedArtifactStore);
        try (RedisFixture restartedProcess = redisStore("m3-s03-clear:")) {
            result = restartedAdapter.restore(
                    new RecoveryTarget(IDENTITY, 2),
                    List.of(first, second),
                    access(),
                    restartedProcess.store());
            assertEquals(
                    List.of("checkpoint-two"), contextText(load(restartedProcess.store())));
        }

        assertEquals(2, result.restoredCandidate().checkpointSequence());
        assertFalse(result.continuityGap());
        assertTrue(result.skippedSnapshots().isEmpty());
    }

    @Test
    void overwritesCorruptRedisHotStateWithATrustedSnapshot() {
        SnapshotCandidate committed = snapshot(3, state("trusted-checkpoint"));
        String prefix = "m3-s03-corrupt:";
        try (JedisPooled jedis = newClient()) {
            String slot = prefix + "session:" + IDENTITY.userId() + "/" + IDENTITY.sessionId();
            jedis.set(slot + ":agent_state", "{not-json");
            jedis.sadd(slot + ":_keys", "agent_state");
        }

        try (RedisFixture replacement = redisStore(prefix)) {
            RecoveryResult result = adapter.restore(
                    new RecoveryTarget(IDENTITY, 3),
                    List.of(committed),
                    access(),
                    replacement.store());

            assertFalse(result.continuityGap());
            assertEquals(
                    List.of("trusted-checkpoint"), contextText(load(replacement.store())));
        }
    }

    @Test
    void fallsBackFromACorruptLatestArtifactAndReportsIntegrityFailure() throws Exception {
        SnapshotCandidate stable = snapshot(9, state("stable-before-corruption"));
        SnapshotCandidate corrupt = snapshot(10, state("latest-to-corrupt"));
        Path corruptBlob = Path.of(artifactStore
                .head(corrupt.artifactId(), access())
                .orElseThrow()
                .storageUri());
        Files.writeString(corruptBlob, "corrupt", StandardCharsets.UTF_8);

        try (RedisFixture redis = redisStore("m3-s03-corrupt-artifact:")) {
            RecoveryResult result = adapter.restore(
                    new RecoveryTarget(IDENTITY, 10),
                    List.of(stable, corrupt),
                    access(),
                    redis.store());

            assertEquals(9, result.restoredCandidate().checkpointSequence());
            assertTrue(result.continuityGap());
            assertEquals(
                    SkipReason.INTEGRITY_VIOLATION,
                    result.skippedSnapshots().get(0).reason());
            assertEquals(
                    List.of("stable-before-corruption"),
                    contextText(load(redis.store())));
        }
    }

    @Test
    void fallsBackFromAMissingLatestArtifactAndReportsAContinuityGap() {
        SnapshotCandidate stable = snapshot(4, state("stable-checkpoint"));
        SnapshotCandidate missing = new SnapshotCandidate(
                ArtifactId.generate(),
                IDENTITY,
                5,
                stable.scope(),
                stable.producer(),
                stable.expectedArtifactHash(),
                stable.declaredSize());

        try (RedisFixture redis = redisStore("m3-s03-missing:")) {
            RecoveryResult result = adapter.restore(
                    new RecoveryTarget(IDENTITY, 5),
                    List.of(stable, missing),
                    access(),
                    redis.store());

            assertEquals(4, result.restoredCandidate().checkpointSequence());
            assertTrue(result.continuityGap());
            assertEquals(SkipReason.MISSING, result.skippedSnapshots().get(0).reason());
            assertEquals(List.of("stable-checkpoint"), contextText(load(redis.store())));
        }
    }

    @Test
    void rejectsCrossIdentityCandidatesWithoutWritingRedis() {
        SnapshotCandidate valid = snapshot(6, state("valid"));

        try (RedisFixture redis = redisStore("m3-s03-injection:")) {
            for (SnapshotIdentity foreign : foreignIdentities()) {
                SnapshotCandidate injected = new SnapshotCandidate(
                        valid.artifactId(),
                        foreign,
                        valid.checkpointSequence(),
                        valid.scope(),
                        producer(foreign),
                        valid.expectedArtifactHash(),
                        valid.declaredSize());

                assertThrows(
                        AgentStateSnapshotRecoveryException.class,
                        () -> adapter.restore(
                                new RecoveryTarget(IDENTITY, 6),
                                List.of(injected),
                                access(),
                                redis.store()));
                assertFalse(redis.store().exists(IDENTITY.userId(), IDENTITY.sessionId()));
            }
        }
    }

    @Test
    void writeFailureDoesNotCreateACommittedCandidateAndNoSnapshotFailsClosed() {
        AgentStateSnapshotAdapter failing = new AgentStateSnapshotAdapter(
                new FailingArtifactStore(artifactStore));

        assertThrows(
                IllegalStateException.class,
                () -> failing.write(writeRequest(7), state("never-committed")));

        try (RedisFixture redis = redisStore("m3-s03-write-failure:")) {
            assertThrows(
                    AgentStateSnapshotRecoveryException.class,
                    () -> adapter.restore(
                            new RecoveryTarget(IDENTITY, 7),
                            List.of(),
                            access(),
                            redis.store()));
            assertFalse(redis.store().exists(IDENTITY.userId(), IDENTITY.sessionId()));
        }
    }

    @Test
    void rejectsOversizedSnapshotsBeforeArtifactPublication() {
        String oversized = "x".repeat(AgentStateSnapshotAdapter.MAX_SNAPSHOT_BYTES);
        AgentState state = AgentState.builder()
                .userId(IDENTITY.userId())
                .sessionId(IDENTITY.sessionId())
                .summary(oversized)
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.write(writeRequest(8), state));
    }

    @Test
    void rejectsAnInconsistentPublishedDescriptorBeforeCreatingACandidate() {
        AgentStateSnapshotAdapter inconsistent = new AgentStateSnapshotAdapter(
                new InconsistentDescriptorArtifactStore(artifactStore));

        assertThrows(
                AgentStateSnapshotPublicationException.class,
                () -> inconsistent.write(writeRequest(11), state("descriptor-mismatch")));
    }

    @Test
    void requiresEverySnapshotToDeclareARetentionPeriod() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WriteRequest(
                        ArtifactId.generate(),
                        ArtifactScope.organization(ORGANIZATION_ID),
                        producer(IDENTITY),
                        IDENTITY,
                        13,
                        CAPTURED_AT,
                        Optional.empty()));
    }

    @Test
    void recomputesContentIntegrityWhenTheStoreReturnsUnverifiedBytes() {
        SnapshotCandidate stable = snapshot(12, state("stable-before-store-bypass"));
        AgentStateSnapshotAdapter untrustedReader = new AgentStateSnapshotAdapter(
                new UnverifiedContentArtifactStore(artifactStore));

        try (RedisFixture redis = redisStore("m3-s03-store-bypass:")) {
            assertThrows(
                    AgentStateSnapshotRecoveryException.class,
                    () -> untrustedReader.restore(
                            new RecoveryTarget(IDENTITY, 12),
                            List.of(stable),
                            access(),
                            redis.store()));
            assertFalse(redis.store().exists(IDENTITY.userId(), IDENTITY.sessionId()));
        }
    }

    private SnapshotCandidate snapshot(long checkpoint, AgentState state) {
        return adapter.write(writeRequest(checkpoint), state);
    }

    private static WriteRequest writeRequest(long checkpoint) {
        return WriteRequest.withDefaultTtl(
                ArtifactId.generate(),
                ArtifactScope.organization(ORGANIZATION_ID),
                producer(IDENTITY),
                IDENTITY,
                checkpoint,
                CAPTURED_AT.plusSeconds(checkpoint));
    }

    private static ArtifactProducer producer(SnapshotIdentity identity) {
        return new ArtifactProducer(
                PRINCIPAL_ID,
                Optional.of(identity.taskExecutionId()),
                Optional.empty(),
                Optional.of(identity.agentRunId()),
                Optional.empty());
    }

    private static List<SnapshotIdentity> foreignIdentities() {
        return List.of(
                identity(UUID.randomUUID(), IDENTITY.agentRunId(), IDENTITY.agentName(),
                        IDENTITY.agentId(), IDENTITY.agentVersion(), IDENTITY.userId(),
                        IDENTITY.sessionId()),
                identity(IDENTITY.taskExecutionId(), UUID.randomUUID(), IDENTITY.agentName(),
                        IDENTITY.agentId(), IDENTITY.agentVersion(), IDENTITY.userId(),
                        IDENTITY.sessionId()),
                identity(IDENTITY.taskExecutionId(), IDENTITY.agentRunId(), "foreign-agent-name",
                        IDENTITY.agentId(), IDENTITY.agentVersion(), IDENTITY.userId(),
                        IDENTITY.sessionId()),
                identity(IDENTITY.taskExecutionId(), IDENTITY.agentRunId(), IDENTITY.agentName(),
                        "foreign-agent-id", IDENTITY.agentVersion(), IDENTITY.userId(),
                        IDENTITY.sessionId()),
                identity(IDENTITY.taskExecutionId(), IDENTITY.agentRunId(), IDENTITY.agentName(),
                        IDENTITY.agentId(), "foreign-version", IDENTITY.userId(),
                        IDENTITY.sessionId()),
                identity(IDENTITY.taskExecutionId(), IDENTITY.agentRunId(), IDENTITY.agentName(),
                        IDENTITY.agentId(), IDENTITY.agentVersion(), "foreign-user",
                        IDENTITY.sessionId()),
                identity(IDENTITY.taskExecutionId(), IDENTITY.agentRunId(), IDENTITY.agentName(),
                        IDENTITY.agentId(), IDENTITY.agentVersion(), IDENTITY.userId(),
                        "foreign-session"));
    }

    private static SnapshotIdentity identity(
            UUID taskExecutionId,
            UUID agentRunId,
            String agentName,
            String agentId,
            String agentVersion,
            String userId,
            String sessionId) {
        return new SnapshotIdentity(
                taskExecutionId,
                agentRunId,
                agentName,
                agentId,
                agentVersion,
                userId,
                sessionId);
    }

    private static AgentState state(String checkpoint) {
        return AgentState.builder()
                .userId(IDENTITY.userId())
                .sessionId(IDENTITY.sessionId())
                .addMessage(new UserMessage(checkpoint))
                .addMessage(AssistantMessage.builder()
                        .name(IDENTITY.agentName())
                        .textContent(checkpoint)
                        .build())
                .build();
    }

    private static List<String> contextText(AgentState state) {
        return state.getContext().stream()
                .map(message -> message.getTextContent())
                .distinct()
                .toList();
    }

    private static AgentState load(AgentStateStore store) {
        return store.get(
                        IDENTITY.userId(),
                        IDENTITY.sessionId(),
                        "agent_state",
                        AgentState.class)
                .orElseThrow();
    }

    private static ArtifactAccessContext access() {
        return new ArtifactAccessContext(
                ORGANIZATION_ID, PRINCIPAL_ID, Set.of(), Set.of());
    }

    private static RedisFixture redisStore(String prefix) {
        JedisPooled client = newClient();
        return new RedisFixture(
                client, RedisDistributedStore.fromJedis(client, prefix).agentStateStore());
    }

    private static JedisPooled newClient() {
        return new JedisPooled(REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT));
    }

    private record RedisFixture(JedisPooled client, AgentStateStore store)
            implements AutoCloseable {

        @Override
        public void close() {
            store.close();
            client.close();
        }
    }

    /** Simulates a process dying before ArtifactStore returns a committed descriptor. */
    private record FailingArtifactStore(ArtifactStore delegate) implements ArtifactStore {

        @Override
        public io.crewscope.application.artifact.ArtifactDescriptor put(
                io.crewscope.application.artifact.ArtifactWriteRequest request,
                java.io.InputStream content) {
            try {
                content.readNBytes(64);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
            throw new IllegalStateException("simulated snapshot publication interruption");
        }

        @Override
        public Optional<io.crewscope.application.artifact.ArtifactDescriptor> head(
                ArtifactId artifactId, ArtifactAccessContext accessContext) {
            return delegate.head(artifactId, accessContext);
        }

        @Override
        public Optional<io.crewscope.application.artifact.ArtifactContent> get(
                ArtifactId artifactId, ArtifactAccessContext accessContext) {
            return delegate.get(artifactId, accessContext);
        }

        @Override
        public Optional<io.crewscope.application.artifact.ArtifactTombstone> tombstone(
                ArtifactId artifactId,
                io.crewscope.application.artifact.ArtifactMutationContext mutationContext,
                io.crewscope.application.artifact.ArtifactTombstoneReason reason,
                Optional<String> detail) {
            return delegate.tombstone(artifactId, mutationContext, reason, detail);
        }

        @Override
        public List<ArtifactId> purgeTombstoned(
                io.crewscope.application.artifact.ArtifactPurgeRequest request) {
            return delegate.purgeTombstoned(request);
        }
    }

    /** Returns a mismatched ID to prove that publication metadata is validated by the Adapter. */
    private record InconsistentDescriptorArtifactStore(ArtifactStore delegate)
            implements ArtifactStore {

        @Override
        public ArtifactDescriptor put(ArtifactWriteRequest request, InputStream content) {
            ArtifactDescriptor descriptor = delegate.put(request, content);
            return new ArtifactDescriptor(
                    ArtifactId.generate(),
                    descriptor.scope(),
                    descriptor.contentType(),
                    descriptor.size(),
                    descriptor.sha256(),
                    descriptor.dataClassification(),
                    descriptor.visibility(),
                    descriptor.storageUri(),
                    descriptor.encryption(),
                    descriptor.producer(),
                    descriptor.createdAt(),
                    descriptor.retentionUntil(),
                    descriptor.tombstone());
        }

        @Override
        public Optional<ArtifactDescriptor> head(
                ArtifactId artifactId, ArtifactAccessContext accessContext) {
            return delegate.head(artifactId, accessContext);
        }

        @Override
        public Optional<ArtifactContent> get(
                ArtifactId artifactId, ArtifactAccessContext accessContext) {
            return delegate.get(artifactId, accessContext);
        }

        @Override
        public Optional<io.crewscope.application.artifact.ArtifactTombstone> tombstone(
                ArtifactId artifactId,
                io.crewscope.application.artifact.ArtifactMutationContext mutationContext,
                io.crewscope.application.artifact.ArtifactTombstoneReason reason,
                Optional<String> detail) {
            return delegate.tombstone(artifactId, mutationContext, reason, detail);
        }

        @Override
        public List<ArtifactId> purgeTombstoned(
                io.crewscope.application.artifact.ArtifactPurgeRequest request) {
            return delegate.purgeTombstoned(request);
        }
    }

    /** Bypasses the Store's normal read verification to test the Adapter's defense in depth. */
    private record UnverifiedContentArtifactStore(ArtifactStore delegate) implements ArtifactStore {

        @Override
        public ArtifactDescriptor put(ArtifactWriteRequest request, InputStream content) {
            return delegate.put(request, content);
        }

        @Override
        public Optional<ArtifactDescriptor> head(
                ArtifactId artifactId, ArtifactAccessContext accessContext) {
            return delegate.head(artifactId, accessContext);
        }

        @Override
        public Optional<ArtifactContent> get(
                ArtifactId artifactId, ArtifactAccessContext accessContext) {
            ArtifactDescriptor descriptor = delegate.head(artifactId, accessContext).orElseThrow();
            byte[] invalid = new byte[Math.toIntExact(descriptor.size())];
            return Optional.of(new ArtifactContent(descriptor, new ByteArrayInputStream(invalid)));
        }

        @Override
        public Optional<io.crewscope.application.artifact.ArtifactTombstone> tombstone(
                ArtifactId artifactId,
                io.crewscope.application.artifact.ArtifactMutationContext mutationContext,
                io.crewscope.application.artifact.ArtifactTombstoneReason reason,
                Optional<String> detail) {
            return delegate.tombstone(artifactId, mutationContext, reason, detail);
        }

        @Override
        public List<ArtifactId> purgeTombstoned(
                io.crewscope.application.artifact.ArtifactPurgeRequest request) {
            return delegate.purgeTombstoned(request);
        }
    }
}
