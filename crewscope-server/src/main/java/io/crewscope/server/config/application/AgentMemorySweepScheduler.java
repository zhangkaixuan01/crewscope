package io.crewscope.server.config.application;

import io.crewscope.application.memory.AgentMemorySweeper;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Non-overlapping wake-up loop for the memory TTL sweep (M10-I02a). Worker-profile only,
 * deliberately not gated by {@code crewscope.memory.enabled}: rows written before a
 * switch-off still deserve their TTL.
 */
final class AgentMemorySweepScheduler {

    private final AgentMemorySweeper sweeper;
    private final AtomicBoolean sweeping = new AtomicBoolean();

    AgentMemorySweepScheduler(AgentMemorySweeper sweeper) {
        this.sweeper = Objects.requireNonNull(sweeper, "sweeper");
    }

    @Scheduled(fixedDelayString = "${crewscope.memory.sweep.poll-interval:1h}")
    void sweep() {
        if (!sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            sweeper.runOnce();
        } finally {
            sweeping.set(false);
        }
    }
}
