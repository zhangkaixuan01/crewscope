package io.crewscope.application.action;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Low-cardinality queue health projection without tenant or Action identifiers. */
public record ActionReconciliationHealth(
        long running,
        long unknown,
        long reconciling,
        long manualReview,
        Optional<UtcTimestamp> oldestUnresolvedAt) {

    public ActionReconciliationHealth {
        if (running < 0 || unknown < 0 || reconciling < 0 || manualReview < 0) {
            throw new IllegalArgumentException("Action health counters must not be negative");
        }
        oldestUnresolvedAt = Objects.requireNonNull(oldestUnresolvedAt, "oldestUnresolvedAt");
    }

    public long unresolved() {
        return Math.addExact(Math.addExact(running, unknown), Math.addExact(reconciling, manualReview));
    }
}
