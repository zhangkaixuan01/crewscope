package io.crewscope.infrastructure.agentscope.snapshot;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.core.util.JsonCodec;
import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.artifact.ArtifactContent;
import io.crewscope.application.artifact.ArtifactDataClassification;
import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactProducer;
import io.crewscope.application.artifact.ArtifactScope;
import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.artifact.ArtifactStoreError;
import io.crewscope.application.artifact.ArtifactStoreException;
import io.crewscope.application.artifact.ArtifactVisibility;
import io.crewscope.application.artifact.ArtifactWriteRequest;
import io.crewscope.application.artifact.Sha256Hash;
import io.crewscope.domain.shared.id.ArtifactId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Writes and restores immutable AgentScope state snapshots through CrewScope ArtifactStore.
 *
 * <p>PostgreSQL remains authoritative for which {@link SnapshotCandidate}s are committed and for
 * the current Task lease/fencing decision. This adapter validates those trusted coordinates,
 * verifies Artifact content, then replaces the Redis-backed {@link AgentStateStore} value.
 */
public final class AgentStateSnapshotAdapter {

    public static final String CONTENT_TYPE =
            "application/vnd.crewscope.agent-state-snapshot+json";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    public static final Duration DEFAULT_TTL = Duration.ofDays(30);
    private static final String AGENT_STATE_KEY = "agent_state";

    private final ArtifactStore artifactStore;
    private final JsonCodec jsonCodec;

    public AgentStateSnapshotAdapter(ArtifactStore artifactStore) {
        this(artifactStore, new JacksonJsonCodec());
    }

    AgentStateSnapshotAdapter(ArtifactStore artifactStore, JsonCodec jsonCodec) {
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
    }

    /**
     * Publishes one complete safe-point state. The caller persists the returned candidate in the
     * same PostgreSQL transaction as its Task/AgentRun checkpoint.
     */
    public SnapshotCandidate write(WriteRequest request, AgentState state) {
        WriteRequest command = Objects.requireNonNull(request, "request");
        AgentState requiredState = Objects.requireNonNull(state, "state");
        requireStateIdentity(command.identity(), requiredState);

        SnapshotEnvelope envelope = new SnapshotEnvelope(
                SCHEMA_VERSION,
                command.identity(),
                command.checkpointSequence(),
                command.capturedAt().toString(),
                requiredState.toJson());
        byte[] content = jsonCodec.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        requireSnapshotSize(content.length);
        Sha256Hash hash = Sha256Hash.digest(content);
        ArtifactWriteRequest artifactRequest = new ArtifactWriteRequest(
                command.artifactId(),
                command.scope(),
                CONTENT_TYPE,
                content.length,
                hash,
                ArtifactDataClassification.RESTRICTED,
                ArtifactVisibility.PRIVATE,
                command.timeToLive(),
                command.producer());
        ArtifactDescriptor descriptor = artifactStore.put(
                artifactRequest, new ByteArrayInputStream(content));
        requirePublishedDescriptor(artifactRequest, descriptor);
        return new SnapshotCandidate(
                descriptor.artifactId(),
                command.identity(),
                command.checkpointSequence(),
                descriptor.scope(),
                descriptor.producer(),
                descriptor.sha256(),
                descriptor.size());
    }

    /**
     * Selects the newest valid committed candidate and overwrites the target Redis state slot.
     * Identity mismatches fail immediately because they indicate cross-run state injection.
     */
    public RecoveryResult restore(
            RecoveryTarget target,
            List<SnapshotCandidate> committedCandidates,
            ArtifactAccessContext accessContext,
            AgentStateStore targetStateStore) {
        RecoveryTarget requiredTarget = Objects.requireNonNull(target, "target");
        List<SnapshotCandidate> candidates = List.copyOf(
                Objects.requireNonNull(committedCandidates, "committedCandidates"));
        ArtifactAccessContext access = Objects.requireNonNull(accessContext, "accessContext");
        AgentStateStore stateStore = Objects.requireNonNull(targetStateStore, "targetStateStore");
        requireCandidateSet(requiredTarget, candidates);

        List<SkippedSnapshot> skipped = new ArrayList<>();
        for (SnapshotCandidate candidate : candidates.stream()
                .sorted(Comparator.comparingLong(SnapshotCandidate::checkpointSequence)
                        .reversed())
                .toList()) {
            Optional<AgentState> restored = readCandidate(candidate, access, skipped);
            if (restored.isEmpty()) {
                continue;
            }
            AgentState state = restored.orElseThrow();
            requireStateIdentity(requiredTarget.identity(), state);
            try {
                stateStore.save(
                        requiredTarget.identity().userId(),
                        requiredTarget.identity().sessionId(),
                        AGENT_STATE_KEY,
                        state);
            } catch (RuntimeException exception) {
                throw new AgentStateSnapshotRecoveryException(
                        "Failed to rebuild the AgentState hot store", exception);
            }
            return new RecoveryResult(
                    candidate,
                    candidate.checkpointSequence()
                            < requiredTarget.committedCheckpointSequence(),
                    skipped);
        }
        throw new AgentStateSnapshotRecoveryException(
                "No valid AgentState snapshot is available for the committed checkpoint");
    }

    private Optional<AgentState> readCandidate(
            SnapshotCandidate candidate,
            ArtifactAccessContext access,
            List<SkippedSnapshot> skipped) {
        Optional<ArtifactContent> stored;
        try {
            stored = artifactStore.get(candidate.artifactId(), access);
        } catch (ArtifactStoreException exception) {
            if (exception.error() == ArtifactStoreError.INTEGRITY_VIOLATION) {
                skipped.add(new SkippedSnapshot(
                        candidate.artifactId(),
                        candidate.checkpointSequence(),
                        SkipReason.INTEGRITY_VIOLATION));
                return Optional.empty();
            }
            throw new AgentStateSnapshotRecoveryException(
                    "AgentState snapshot storage is unavailable", exception);
        }
        if (stored.isEmpty()) {
            skipped.add(new SkippedSnapshot(
                    candidate.artifactId(),
                    candidate.checkpointSequence(),
                    SkipReason.MISSING));
            return Optional.empty();
        }

        try (ArtifactContent artifact = stored.orElseThrow()) {
            ArtifactDescriptor descriptor = artifact.descriptor();
            if (!descriptor.artifactId().equals(candidate.artifactId())
                    || !CONTENT_TYPE.equals(descriptor.contentType())
                    || !descriptor.scope().equals(candidate.scope())
                    || !descriptor.producer().equals(candidate.producer())
                    || descriptor.dataClassification()
                            != ArtifactDataClassification.RESTRICTED
                    || descriptor.visibility() != ArtifactVisibility.PRIVATE
                    || descriptor.size() != candidate.declaredSize()
                    || !descriptor.sha256().equals(candidate.expectedArtifactHash())) {
                skipped.add(new SkippedSnapshot(
                        candidate.artifactId(),
                        candidate.checkpointSequence(),
                        SkipReason.METADATA_MISMATCH));
                return Optional.empty();
            }
            if (!isValidSnapshotSize(descriptor.size())) {
                skipped.add(new SkippedSnapshot(
                        candidate.artifactId(),
                        candidate.checkpointSequence(),
                        SkipReason.METADATA_MISMATCH));
                return Optional.empty();
            }
            byte[] content;
            try {
                content = readBounded(artifact.stream());
            } catch (SnapshotContentIntegrityException exception) {
                skipped.add(new SkippedSnapshot(
                        candidate.artifactId(),
                        candidate.checkpointSequence(),
                        SkipReason.INTEGRITY_VIOLATION));
                return Optional.empty();
            }
            if (content.length != descriptor.size()
                    || !Sha256Hash.digest(content).equals(descriptor.sha256())) {
                skipped.add(new SkippedSnapshot(
                        candidate.artifactId(),
                        candidate.checkpointSequence(),
                        SkipReason.INTEGRITY_VIOLATION));
                return Optional.empty();
            }
            SnapshotEnvelope envelope;
            AgentState state;
            try {
                envelope = jsonCodec.fromJson(
                        new String(content, StandardCharsets.UTF_8), SnapshotEnvelope.class);
                requireEnvelopeIdentity(candidate, envelope);
                state = AgentState.fromJsonString(envelope.agentStateJson());
            } catch (IdentityMismatchException exception) {
                throw new AgentStateSnapshotRecoveryException(
                        "AgentState snapshot identity does not match the committed candidate");
            } catch (RuntimeException exception) {
                skipped.add(new SkippedSnapshot(
                        candidate.artifactId(),
                        candidate.checkpointSequence(),
                        SkipReason.INVALID_ENVELOPE));
                return Optional.empty();
            }
            requireStateIdentity(candidate.identity(), state);
            return Optional.of(state);
        } catch (IOException exception) {
            throw new AgentStateSnapshotRecoveryException(
                    "Failed to close the AgentState snapshot stream", exception);
        }
    }

    private static byte[] readBounded(InputStream input) {
        try {
            byte[] content = input.readNBytes(MAX_SNAPSHOT_BYTES + 1);
            if (!isValidSnapshotSize(content.length)) {
                throw new SnapshotContentIntegrityException();
            }
            return content;
        } catch (IOException exception) {
            throw new AgentStateSnapshotRecoveryException(
                    "Failed to read the AgentState snapshot", exception);
        }
    }

    private static void requireCandidateSet(
            RecoveryTarget target, List<SnapshotCandidate> candidates) {
        Set<Long> sequences = new HashSet<>();
        for (SnapshotCandidate candidate : candidates) {
            if (!target.identity().equals(candidate.identity())) {
                throw new AgentStateSnapshotRecoveryException(
                        "AgentState snapshot candidate identity does not match the recovery target");
            }
            if (candidate.checkpointSequence() > target.committedCheckpointSequence()) {
                throw new AgentStateSnapshotRecoveryException(
                        "AgentState snapshot is ahead of the committed checkpoint");
            }
            if (!sequences.add(candidate.checkpointSequence())) {
                throw new AgentStateSnapshotRecoveryException(
                        "Duplicate AgentState snapshot checkpoint sequence");
            }
        }
    }

    private static void requirePublishedDescriptor(
            ArtifactWriteRequest request, ArtifactDescriptor descriptor) {
        ArtifactDescriptor published = Objects.requireNonNull(descriptor, "descriptor");
        if (!published.matches(request)
                || published.tombstone().isPresent()
                || published.retentionUntil().isEmpty()) {
            throw new AgentStateSnapshotPublicationException(
                    "ArtifactStore returned an inconsistent AgentState snapshot descriptor");
        }
    }

    private static void requireEnvelopeIdentity(
            SnapshotCandidate candidate, SnapshotEnvelope envelope) {
        if (envelope == null
                || envelope.schemaVersion() != SCHEMA_VERSION
                || !candidate.identity().equals(envelope.identity())
                || candidate.checkpointSequence() != envelope.checkpointSequence()) {
            throw new IdentityMismatchException();
        }
        try {
            Instant.parse(envelope.capturedAt());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid snapshot capture timestamp", exception);
        }
        if (envelope.agentStateJson() == null || envelope.agentStateJson().isBlank()) {
            throw new IllegalArgumentException("AgentState snapshot payload is blank");
        }
    }

    private static void requireStateIdentity(SnapshotIdentity identity, AgentState state) {
        if (!identity.userId().equals(state.getUserId())
                || !identity.sessionId().equals(state.getSessionId())) {
            throw new AgentStateSnapshotRecoveryException(
                    "AgentState identity does not match the trusted snapshot coordinates");
        }
    }

    private static void requireSnapshotSize(long size) {
        if (!isValidSnapshotSize(size)) {
            throw new IllegalArgumentException(
                    "AgentState snapshot must contain 1 to " + MAX_SNAPSHOT_BYTES + " bytes");
        }
    }

    private static boolean isValidSnapshotSize(long size) {
        return size > 0 && size <= MAX_SNAPSHOT_BYTES;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    /** Stable runtime coordinates pinned by the Task Agent version and AgentRun. */
    public record SnapshotIdentity(
            UUID taskExecutionId,
            UUID agentRunId,
            String agentName,
            String agentId,
            String agentVersion,
            String userId,
            String sessionId) {

        public SnapshotIdentity {
            taskExecutionId = requireUuid(taskExecutionId, "taskExecutionId");
            agentRunId = requireUuid(agentRunId, "agentRunId");
            agentName = requireText(agentName, "agentName", 200);
            agentId = requireText(agentId, "agentId", 200);
            agentVersion = requireText(agentVersion, "agentVersion", 100);
            userId = requireText(userId, "userId", 500);
            sessionId = requireText(sessionId, "sessionId", 500);
        }
    }

    /** Safe-point write input; PostgreSQL persists the returned candidate after Artifact commit. */
    public record WriteRequest(
            ArtifactId artifactId,
            ArtifactScope scope,
            ArtifactProducer producer,
            SnapshotIdentity identity,
            long checkpointSequence,
            Instant capturedAt,
            Optional<Duration> timeToLive) {

        public WriteRequest {
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(producer, "producer");
            Objects.requireNonNull(identity, "identity");
            requireProducerIdentity(producer, identity);
            if (checkpointSequence < 1) {
                throw new IllegalArgumentException("checkpointSequence must be positive");
            }
            Objects.requireNonNull(capturedAt, "capturedAt");
            timeToLive = Objects.requireNonNull(timeToLive, "timeToLive");
            if (timeToLive.isEmpty()) {
                throw new IllegalArgumentException("AgentState snapshot timeToLive is required");
            }
        }

        public static WriteRequest withDefaultTtl(
                ArtifactId artifactId,
                ArtifactScope scope,
                ArtifactProducer producer,
                SnapshotIdentity identity,
                long checkpointSequence,
                Instant capturedAt) {
            return new WriteRequest(
                    artifactId,
                    scope,
                    producer,
                    identity,
                    checkpointSequence,
                    capturedAt,
                    Optional.of(DEFAULT_TTL));
        }
    }

    /** PostgreSQL-projected metadata for one completely committed snapshot. */
    public record SnapshotCandidate(
            ArtifactId artifactId,
            SnapshotIdentity identity,
            long checkpointSequence,
            ArtifactScope scope,
            ArtifactProducer producer,
            Sha256Hash expectedArtifactHash,
            long declaredSize) {

        public SnapshotCandidate {
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(identity, "identity");
            if (checkpointSequence < 1) {
                throw new IllegalArgumentException("checkpointSequence must be positive");
            }
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(producer, "producer");
            requireProducerIdentity(producer, identity);
            Objects.requireNonNull(expectedArtifactHash, "expectedArtifactHash");
            requireSnapshotSize(declaredSize);
        }
    }

    /** Current PostgreSQL recovery coordinates after Lease/Fencing validation. */
    public record RecoveryTarget(SnapshotIdentity identity, long committedCheckpointSequence) {

        public RecoveryTarget {
            Objects.requireNonNull(identity, "identity");
            if (committedCheckpointSequence < 1) {
                throw new IllegalArgumentException(
                        "committedCheckpointSequence must be positive");
            }
        }
    }

    public record RecoveryResult(
            SnapshotCandidate restoredCandidate,
            boolean continuityGap,
            List<SkippedSnapshot> skippedSnapshots) {

        public RecoveryResult {
            Objects.requireNonNull(restoredCandidate, "restoredCandidate");
            skippedSnapshots = List.copyOf(
                    Objects.requireNonNull(skippedSnapshots, "skippedSnapshots"));
        }
    }

    public record SkippedSnapshot(
            ArtifactId artifactId, long checkpointSequence, SkipReason reason) {

        public SkippedSnapshot {
            Objects.requireNonNull(artifactId, "artifactId");
            if (checkpointSequence < 1) {
                throw new IllegalArgumentException("checkpointSequence must be positive");
            }
            Objects.requireNonNull(reason, "reason");
        }
    }

    public enum SkipReason {
        MISSING,
        INTEGRITY_VIOLATION,
        METADATA_MISMATCH,
        INVALID_ENVELOPE
    }

    private record SnapshotEnvelope(
            int schemaVersion,
            SnapshotIdentity identity,
            long checkpointSequence,
            String capturedAt,
            String agentStateJson) {}

    private static UUID requireUuid(UUID value, String field) {
        UUID required = Objects.requireNonNull(value, field);
        if (required.equals(new UUID(0, 0))) {
            throw new IllegalArgumentException(field + " must not use the nil UUID");
        }
        return required;
    }

    private static void requireProducerIdentity(
            ArtifactProducer producer, SnapshotIdentity identity) {
        if (!producer.taskExecutionId().equals(Optional.of(identity.taskExecutionId()))
                || !producer.agentRunId().equals(Optional.of(identity.agentRunId()))) {
            throw new IllegalArgumentException(
                    "Artifact producer must match the snapshot TaskExecution and AgentRun");
        }
    }

    private static final class IdentityMismatchException extends RuntimeException {}

    /** Signals that the content stream violates its already validated Descriptor boundary. */
    private static final class SnapshotContentIntegrityException extends RuntimeException {}
}
