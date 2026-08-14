package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentStateSnapshotAndRuntimeArtifactTest {

    private static final UtcTimestamp T1 = UtcTimestamp.parse("2026-08-13T10:11:00Z");
    private static final UtcTimestamp T2 = UtcTimestamp.parse("2026-08-13T10:12:00Z");
    private static final UtcTimestamp T3 = UtcTimestamp.parse("2026-08-13T10:13:00Z");

    @Test
    void capturesClosedSnapshotIdentityAndHashFromArtifactMetadata() {
        SnapshotFixture fixture = new SnapshotFixture();

        AgentStateSnapshot snapshot = fixture.snapshot(1, 5, fixture.artifact, T2);

        assertEquals(fixture.session.id(), snapshot.runtimeSessionId());
        assertEquals(fixture.run.id(), snapshot.agentRunId());
        assertEquals(fixture.artifact.id(), snapshot.runtimeArtifactId());
        assertEquals(fixture.artifact.contentHash(), snapshot.contentHash());
        assertEquals(fixture.session.agentScopeKey(), snapshot.agentScopeKey());
        assertEquals(AgentStateSnapshotStatus.CURRENT, snapshot.status());
        assertTrue(snapshot.status().isRecoveryCandidate());
    }

    @Test
    void rejectsOversizedSnapshotAndNonSnapshotArtifact() {
        SnapshotFixture fixture = new SnapshotFixture();
        RuntimeArtifact oversized = fixture.artifact(
                RuntimeArtifactKind.AGENT_STATE_SNAPSHOT,
                AgentStateSnapshot.CONTENT_TYPE,
                AgentStateSnapshot.MAX_SNAPSHOT_SIZE + 1,
                T2);
        RuntimeArtifact modelResult = fixture.artifact(
                RuntimeArtifactKind.MODEL_RESULT, "application/json", 100, T2);

        assertThrows(
                DomainValidationException.class,
                () -> fixture.snapshot(1, 1, oversized, T2));
        assertThrows(
                DomainValidationException.class,
                () -> fixture.snapshot(1, 1, modelResult, T2));
    }

    @Test
    void supersedesCurrentSnapshotButKeepsItAsFallbackCandidate() {
        SnapshotFixture fixture = new SnapshotFixture();
        AgentStateSnapshot first = fixture.snapshot(1, 5, fixture.artifact, T2);
        RuntimeArtifact newerArtifact = fixture.artifact(
                RuntimeArtifactKind.AGENT_STATE_SNAPSHOT,
                AgentStateSnapshot.CONTENT_TYPE,
                2_048,
                T3);
        AgentStateSnapshot newer = fixture.snapshot(2, 6, newerArtifact, T3);

        AgentStateSnapshot superseded = first.supersedeBy(
                newer, 0, fixture.fixture.planning.base.executor, T3);

        assertEquals(AgentStateSnapshotStatus.SUPERSEDED, superseded.status());
        assertTrue(superseded.status().isRecoveryCandidate());
        assertEquals(AgentStateSnapshotStatus.INVALID,
                superseded.invalidate(
                                "ARTIFACT_CORRUPT",
                                1,
                                fixture.fixture.planning.base.owner,
                                T3)
                        .status());
    }

    @Test
    void runtimeArtifactStoresOnlyExternalReferenceIntegrityAndRetentionMetadata() {
        SnapshotFixture fixture = new SnapshotFixture();

        RuntimeArtifact artifact = fixture.artifact;

        assertEquals(ArtifactId.class, artifact.artifactId().getClass());
        assertEquals(64, artifact.contentHash().value().length());
        assertEquals(
                UtcTimestamp.from(T1.value().plus(Duration.ofDays(30))),
                artifact.retentionUntil().orElseThrow());
    }

    private static final class SnapshotFixture {
        final TaskAgentRuntimeSessionTest.RuntimeFixture fixture =
                new TaskAgentRuntimeSessionTest.RuntimeFixture();
        final TaskAgentRuntimeSession session = fixture.stepSession();
        final AgentRun run = AgentRun.start(
                AgentRunId.generate(), session, 1, fixture.planning.base.executor, T1);
        final RuntimeArtifact artifact = artifact(
                RuntimeArtifactKind.AGENT_STATE_SNAPSHOT,
                AgentStateSnapshot.CONTENT_TYPE,
                1_024,
                T1);

        RuntimeArtifact artifact(
                RuntimeArtifactKind kind, String contentType, long size, UtcTimestamp occurredAt) {
            return RuntimeArtifact.register(
                    RuntimeArtifactId.generate(),
                    ArtifactId.generate(),
                    run,
                    kind,
                    contentType,
                    size,
                    RuntimeContentHash.sha256(kind + ":" + size + ":" + occurredAt),
                    Optional.of(UtcTimestamp.from(T1.value().plus(Duration.ofDays(30)))),
                    fixture.planning.base.executor,
                    occurredAt);
        }

        AgentStateSnapshot snapshot(
                long snapshotSequence,
                long checkpointSequence,
                RuntimeArtifact artifact,
                UtcTimestamp occurredAt) {
            return AgentStateSnapshot.capture(
                    AgentStateSnapshotId.generate(),
                    session,
                    run,
                    artifact,
                    "task_orchestrator",
                    snapshotSequence,
                    checkpointSequence,
                    fixture.planning.base.executor,
                    occurredAt);
        }
    }
}
