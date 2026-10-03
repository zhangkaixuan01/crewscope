package io.crewscope.application.retrieval;

import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable persistence Port for knowledge-index jobs and their batch checkpoints
 * (M10-I01b). Claiming is a single CTE {@code FOR UPDATE SKIP LOCKED} over live jobs
 * (QUEUED, or in-flight with an expired lease) ordered by {@code created_at}; the claim
 * itself bumps {@code attempt} and the monotonic {@code claimToken}, enters CHUNKING
 * and installs the lease. Every worker write is guarded by that token — an empty
 * {@link #updateClaimed} or a false {@link #insertCheckpoint} is an observable FENCE.
 */
public interface KnowledgeIndexJobRepository {

    /**
     * Persists one job. Concurrent creates for the same still-live target (entry or
     * index coordinate) collapse onto the partial unique indexes — the loser surfaces
     * the constraint violation, which callers may translate into a retry that then
     * observes the winner through the find-live lookups.
     */
    KnowledgeIndexJob create(KnowledgeIndexJob job);

    Optional<KnowledgeIndexJob> findById(
            OrganizationId organizationId, TeamId teamId, UUID jobId);

    /** The live job of one knowledge entry, if any (enqueue idempotency). */
    Optional<KnowledgeIndexJob> findLiveByEntry(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId);

    /** The live job of one repository index coordinate, if any (enqueue idempotency). */
    Optional<KnowledgeIndexJob> findLiveByIndexKey(RepositoryIndexKey indexKey);

    /** The most recent job of one entry regardless of state (indexStatus projection). */
    Optional<KnowledgeIndexJob> findLatestByEntry(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId);

    /**
     * Lists the Team's jobs ordered by {@code (createdAt, id)} ascending (M10-I01c
     * control-plane read). Keyset cursor: {@code pageRequest.afterJobId()} is the last
     * job id of the previous page; the cursor must resolve within the same Team's jobs,
     * otherwise callers reject the request.
     */
    KnowledgeIndexJobPage findByTeam(
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeIndexJobFilter filter,
            KnowledgeIndexJobPageRequest pageRequest);

    /**
     * Atomically claims the oldest live job: attempt+1, claimToken+1, CHUNKING, lease
     * installed. The returned job already carries the new claim state.
     */
    Optional<KnowledgeIndexJob> claimNext(String owner, UtcTimestamp now, Duration leaseDuration);

    /**
     * Commits the desired next state only while the caller's claim is still live
     * (id + claimToken + claimedBy + unexpired lease + non-terminal status). A terminal
     * next state clears the lease; any other renews it. Empty means FENCED.
     */
    Optional<KnowledgeIndexJob> updateClaimed(KnowledgeIndexJob job, String owner,
            UtcTimestamp now, Duration leaseDuration);

    /**
     * Appends one batch checkpoint only while the job still carries the given claim
     * token and is not terminal; {@code false} means FENCED.
     */
    boolean insertCheckpoint(UUID jobId, long claimToken, int chunkSeq, int chunkCount);

    /**
     * Highest committed chunk position of one job — the tail of the last committed
     * batch, not its head — so a resume starts at max + 1 exactly on the next batch
     * boundary. Zero when nothing committed yet.
     */
    int maxCheckpointSeq(UUID jobId);

    /** Cancels a still-QUEUED job; empty when it already left QUEUED. */
    Optional<KnowledgeIndexJob> cancelQueued(KnowledgeIndexJob job, UtcTimestamp cancelledAt);
}
