package io.crewscope.application.execution;

import io.crewscope.domain.model.ModelCallAttribution;
import io.crewscope.domain.model.ModelTokenUsage;
import java.util.Objects;

/**
 * Port for emitting one realtime Task-runtime chat usage fact (M10-F03). The deterministic
 * call/event ids derive from the execution coordinates, so a replayed stream appends
 * nothing the second time and the rollup never double counts.
 */
public interface RealtimeUsageFactEmitter {

    /**
     * Appends one {@code MODEL_USAGE_FACT_RECORDED} for the call that just reported
     * {@code usage} at the given durable event coordinates.
     *
     * @return true when a new fact was appended; false when its deterministic event id
     *         already exists (replay).
     */
    boolean emit(
            TaskExecutionRuntimeFacts facts,
            ModelCallAttribution attribution,
            ModelTokenUsage usage,
            long segmentSequence,
            long sequence);

    /** Canonical no-op for deployments without domain-event wiring. */
    static RealtimeUsageFactEmitter disabled() {
        return (facts, attribution, usage, segmentSequence, sequence) -> {
            Objects.requireNonNull(facts, "facts");
            return false;
        };
    }
}
