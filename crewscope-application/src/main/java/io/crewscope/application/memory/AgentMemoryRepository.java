package io.crewscope.application.memory;

import io.crewscope.domain.agent.AgentMemoryEntry;
import io.crewscope.domain.agent.AgentMemoryKey;
import io.crewscope.domain.agent.AgentMemoryOwner;
import io.crewscope.domain.agent.AgentMemoryOwnerKey;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Optional;

/**
 * Persistence Port for the agent assistant memory lifecycle (M10-I02a, S01 §3.6).
 *
 * <p>Visibility is generation-gated: {@link #findVisible} only returns entries whose
 * clearance generation equals the owner's current one and whose TTL has not expired, so a
 * cleared space can never be read back. {@link #upsert} locks the owner row
 * ({@code SELECT ... FOR UPDATE}) and rejects a stale clearance generation with
 * {@link io.crewscope.domain.shared.error.OptimisticLockConflictException}, so an in-flight
 * write racing a clear can never resurrect old rows either.
 */
public interface AgentMemoryRepository {

    /** Creates the (member x Agent) owner row if absent; returns the current committed row. */
    AgentMemoryOwner ensureOwner(AgentMemoryOwnerKey key, PrincipalId actor, UtcTimestamp now);

    /** The owner row if the space was ever written or cleared. */
    Optional<AgentMemoryOwner> findOwner(AgentMemoryOwnerKey key);

    /** Current-generation, unexpired entries of exactly one policy space, memory_key ascending. */
    List<AgentMemoryEntry> findVisible(
            AgentMemoryOwnerKey key, AgentMemoryPolicyReference policy, UtcTimestamp now);

    /**
     * Inserts the entry or overwrites the existing value in the same space; the owner row is
     * locked and the entry's clearance generation must match, otherwise
     * {@link io.crewscope.domain.shared.error.OptimisticLockConflictException}.
     */
    AgentMemoryEntry upsert(AgentMemoryEntry entry);

    /** Slides the TTL of one visible entry; returns false when it is absent, expired or stale. */
    boolean renew(
            AgentMemoryOwnerKey key,
            AgentMemoryPolicyReference policy,
            AgentMemoryKey memoryKey,
            UtcTimestamp newExpiresAt,
            PrincipalId actor,
            UtcTimestamp now);

    /** Clears the space: generation +1, then deletes every current-generation entry (all spaces). */
    AgentMemoryClearance clear(AgentMemoryOwnerKey key, PrincipalId actor, UtcTimestamp now);

    /** Physically deletes expired and stale-generation entries, at most {@code limit} rows. */
    long deleteSweepable(UtcTimestamp now, int limit);
}
