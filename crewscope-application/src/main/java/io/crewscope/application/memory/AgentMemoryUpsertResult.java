package io.crewscope.application.memory;

import io.crewscope.domain.agent.AgentMemoryEntry;
import java.util.Optional;

/**
 * Outcome of one runtime memory write (M10-I02a, S01 §3.6). Capacity exhaustion is an
 * explicit rejection with the counts the caller needs to prompt a cleanup — never a silent
 * eviction — and a stale clearance generation rejects rather than resurrects cleared memory.
 */
public record AgentMemoryUpsertResult(
        Outcome outcome,
        Optional<AgentMemoryEntry> entry,
        int entryCount,
        int maxEntries) {

    public AgentMemoryUpsertResult {
        entry = Optional.ofNullable(entry).orElse(Optional.empty());
    }

    public enum Outcome {
        /** The value was written (inserted or overwritten) and the TTL deadline refreshed. */
        WRITTEN,
        /** The deployment switch {@code crewscope.memory.enabled} is off: no new model memory. */
        MEMORY_DISABLED,
        /** The Agent's current configuration carries no memory policy: memory is off. */
        NOT_CONFIGURED,
        /** The referenced policy version is not resolvable from the catalog. */
        POLICY_UNAVAILABLE,
        /** The policy space already holds maxEntries visible entries; nothing was written. */
        CAPACITY_EXCEEDED,
        /** The write raced a clear and its clearance generation is stale; nothing was written. */
        STALE_CLEARANCE
    }
}
