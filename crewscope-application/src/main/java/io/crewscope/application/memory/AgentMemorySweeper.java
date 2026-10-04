package io.crewscope.application.memory;

import io.crewscope.domain.shared.time.TimeProvider;
import java.util.Objects;

/**
 * One TTL sweep pass (M10-I02a): physically deletes expired entries and stale-generation
 * survivors of raced clears, in bounded batches. The sweep is not gated by the deployment
 * switch — cleanup keeps running with memory off, because rows written before a switch-off
 * still deserve their TTL.
 */
public final class AgentMemorySweeper {

    /** Bounded batch per pass; the scheduler's fixed delay paces the rest. */
    public static final int SWEEP_BATCH = 500;

    private final AgentMemoryRepository memory;
    private final TimeProvider timeProvider;

    public AgentMemorySweeper(AgentMemoryRepository memory, TimeProvider timeProvider) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Deletes at most {@link #SWEEP_BATCH} sweepable rows and reports how many went. */
    public long runOnce() {
        return memory.deleteSweepable(timeProvider.now(), SWEEP_BATCH);
    }
}
