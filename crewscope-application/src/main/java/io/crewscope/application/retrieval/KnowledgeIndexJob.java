package io.crewscope.application.retrieval;

import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable fact of one knowledge-index build (M10-I01b). A job targets exactly one of the
 * two sources: a knowledge entry (target = (organization, team, entryId); the effective
 * revision is re-read at claim time through the authoritative gate, never captured at
 * enqueue time, so stale events cannot resurrect retired content) or a repository index
 * build (target = the full {@link RepositoryIndexKey} six-tuple including the chunking
 * policy hash and the embedding model revision frozen at enqueue time).
 *
 * <p>{@code claimToken} is the monotonic fencing counter (V30 precedent): every worker
 * write is guarded by (id, claimToken, claimedBy, unexpired lease, non-terminal status),
 * and a zero-row update is an observable FENCE, not a silent loss. Terminal states never
 * carry a lease.
 */
public record KnowledgeIndexJob(
        UUID id,
        OrganizationId organizationId,
        TeamId teamId,
        KnowledgeIndexJobSource source,
        Optional<KnowledgeEntryId> entryId,
        Optional<WorkProjectId> projectId,
        Optional<RepositoryIndexKey> indexKey,
        KnowledgeIndexJobStatus status,
        int attempt,
        int chunksDone,
        int chunksTotal,
        Optional<String> failureCode,
        long generationBuildSequence,
        Optional<String> claimedBy,
        long claimToken,
        Optional<UtcTimestamp> leaseExpiresAt,
        PrincipalId createdBy,
        UtcTimestamp createdAt,
        UtcTimestamp updatedAt) {

    public KnowledgeIndexJob {
        id = Objects.requireNonNull(id, "id");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        source = Objects.requireNonNull(source, "source");
        entryId = Objects.requireNonNull(entryId, "entryId");
        projectId = Objects.requireNonNull(projectId, "projectId");
        indexKey = Objects.requireNonNull(indexKey, "indexKey");
        status = Objects.requireNonNull(status, "status");
        if (attempt < 0 || claimToken < 0 || generationBuildSequence < 0) {
            throw new IllegalArgumentException(
                    "attempt, claimToken and generationBuildSequence must not be negative");
        }
        if (chunksDone < 0 || chunksTotal < 0 || chunksDone > chunksTotal) {
            throw new IllegalArgumentException("chunk counts must satisfy 0 <= done <= total");
        }
        failureCode = Objects.requireNonNull(failureCode, "failureCode")
                .map(value -> requireText(value, 80, "failureCode"));
        claimedBy = Objects.requireNonNull(claimedBy, "claimedBy")
                .map(value -> requireText(value, 160, "claimedBy"));
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        requireTargetShape(source, entryId, projectId, indexKey);
        requireStateInvariants(status, failureCode, claimedBy, leaseExpiresAt);
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.compareTo(createdAt) < 0) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }

    /** QUEUED job re-indexing one knowledge entry. */
    public static KnowledgeIndexJob knowledgeEntry(
            UUID id,
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryId entryId,
            PrincipalId createdBy,
            UtcTimestamp now) {
        return new KnowledgeIndexJob(
                id, organizationId, teamId, KnowledgeIndexJobSource.KNOWLEDGE_ENTRY,
                Optional.of(entryId), Optional.empty(), Optional.empty(),
                KnowledgeIndexJobStatus.QUEUED, 0, 0, 0, Optional.empty(), 0,
                Optional.empty(), 0, Optional.empty(),
                createdBy, now, now);
    }

    /** QUEUED job building one repository index generation. */
    public static KnowledgeIndexJob repositoryBuild(
            UUID id,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            RepositoryIndexKey indexKey,
            PrincipalId createdBy,
            UtcTimestamp now) {
        return new KnowledgeIndexJob(
                id, organizationId, teamId, KnowledgeIndexJobSource.REPOSITORY,
                Optional.empty(), Optional.of(projectId), Optional.of(indexKey),
                KnowledgeIndexJobStatus.QUEUED, 0, 0, 0, Optional.empty(), 0,
                Optional.empty(), 0, Optional.empty(),
                createdBy, now, now);
    }

    /** The claim transition: attempt+1, claimToken+1, CHUNKING, lease installed. */
    public KnowledgeIndexJob asClaimed(String owner, UtcTimestamp leaseUntil, UtcTimestamp now) {
        return new KnowledgeIndexJob(
                id, organizationId, teamId, source, entryId, projectId, indexKey,
                KnowledgeIndexJobStatus.CHUNKING, attempt + 1, chunksDone, chunksTotal,
                Optional.empty(), generationBuildSequence,
                Optional.of(requireText(owner, 160, "owner")), claimToken + 1,
                Optional.of(leaseUntil), createdBy, createdAt, now);
    }

    /** Moves between live states; progress and lease ownership stay untouched. */
    public KnowledgeIndexJob withStatus(KnowledgeIndexJobStatus next, UtcTimestamp now) {
        requireLive(next, "next");
        requireLive(status, "current");
        return new KnowledgeIndexJob(
                id, organizationId, teamId, source, entryId, projectId, indexKey,
                next, attempt, chunksDone, chunksTotal, failureCode, generationBuildSequence,
                claimedBy, claimToken, leaseExpiresAt, createdBy, createdAt, now);
    }

    public KnowledgeIndexJob withProgress(int done, int total, UtcTimestamp now) {
        return new KnowledgeIndexJob(
                id, organizationId, teamId, source, entryId, projectId, indexKey,
                status, attempt, done, total, failureCode, generationBuildSequence,
                claimedBy, claimToken, leaseExpiresAt, createdBy, createdAt, now);
    }

    /** Pins the build sequence of the generation this job opened (resume anchor). */
    public KnowledgeIndexJob withGeneration(long buildSequence, UtcTimestamp now) {
        if (buildSequence < 1) {
            throw new IllegalArgumentException("generationBuildSequence must be positive");
        }
        return new KnowledgeIndexJob(
                id, organizationId, teamId, source, entryId, projectId, indexKey,
                status, attempt, chunksDone, chunksTotal, failureCode, buildSequence,
                claimedBy, claimToken, leaseExpiresAt, createdBy, createdAt, now);
    }

    /** Terminal success: lease-free READY. */
    public KnowledgeIndexJob ready(UtcTimestamp now) {
        return terminal(KnowledgeIndexJobStatus.READY, null, now);
    }

    public KnowledgeIndexJob failed(String code, UtcTimestamp now) {
        return terminal(KnowledgeIndexJobStatus.FAILED, code, now);
    }

    public KnowledgeIndexJob cancelled(String code, UtcTimestamp now) {
        return terminal(KnowledgeIndexJobStatus.CANCELLED, code, now);
    }

    private KnowledgeIndexJob terminal(
            KnowledgeIndexJobStatus next, String code, UtcTimestamp now) {
        requireLive(status, "current");
        return new KnowledgeIndexJob(
                id, organizationId, teamId, source, entryId, projectId, indexKey,
                next, attempt, chunksDone, chunksTotal,
                Optional.ofNullable(code), generationBuildSequence,
                Optional.empty(), claimToken, Optional.empty(), createdBy, createdAt, now);
    }

    private static void requireTargetShape(
            KnowledgeIndexJobSource source,
            Optional<KnowledgeEntryId> entryId,
            Optional<WorkProjectId> projectId,
            Optional<RepositoryIndexKey> indexKey) {
        boolean knowledge = source == KnowledgeIndexJobSource.KNOWLEDGE_ENTRY;
        if (knowledge != entryId.isPresent()
                || knowledge == projectId.isPresent()
                || knowledge == indexKey.isPresent()) {
            throw new IllegalArgumentException(
                    "exactly one target shape must match the source");
        }
    }

    private static void requireStateInvariants(
            KnowledgeIndexJobStatus status,
            Optional<String> failureCode,
            Optional<String> claimedBy,
            Optional<UtcTimestamp> leaseExpiresAt) {
        boolean terminal = status.terminal();
        boolean hasFailureCode = failureCode.isPresent();
        boolean expectedCode = status == KnowledgeIndexJobStatus.FAILED
                || status == KnowledgeIndexJobStatus.CANCELLED;
        if (expectedCode != hasFailureCode) {
            throw new IllegalArgumentException(
                    "only a FAILED or CANCELLED job carries a failure code, "
                            + "and they always do");
        }
        boolean hasLease = claimedBy.isPresent();
        if (hasLease != leaseExpiresAt.isPresent()) {
            throw new IllegalArgumentException("claimedBy and leaseExpiresAt come in pairs");
        }
        if (terminal && hasLease) {
            throw new IllegalArgumentException("terminal states never carry a lease");
        }
        if (!terminal && status != KnowledgeIndexJobStatus.QUEUED && !hasLease) {
            throw new IllegalArgumentException("a claimed live job always carries a lease");
        }
    }

    private static void requireLive(KnowledgeIndexJobStatus value, String label) {
        if (value.terminal()) {
            throw new IllegalArgumentException(label + " status must be live, got " + value);
        }
    }

    private static String requireText(String value, int maxLength, String field) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must contain 1 to " + maxLength + " characters");
        }
        return normalized;
    }
}
