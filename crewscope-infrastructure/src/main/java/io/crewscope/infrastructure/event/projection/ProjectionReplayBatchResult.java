package io.crewscope.infrastructure.event.projection;

import java.util.Objects;
import java.util.Optional;

/** Durable, restartable outcome of one bounded shadow-history replay page. */
public record ProjectionReplayBatchResult(
        int scanned,
        int applied,
        int duplicateOrStale,
        boolean leaseRejected,
        Optional<ProjectionHistoryCursor> nextCursor) {

    public ProjectionReplayBatchResult {
        if (scanned < 0 || applied < 0 || duplicateOrStale < 0
                || applied + duplicateOrStale > scanned) {
            throw new IllegalArgumentException("Replay counters are invalid");
        }
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    }

    public boolean caughtUp() {
        return scanned == 0;
    }
}
