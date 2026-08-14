package io.crewscope.domain.task;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Immutable terminal fact explaining when and why ownership ended. */
public record ExecutionLeaseRelease(
        ExecutionLeaseReleaseReason reason, UtcTimestamp releasedAt) {

    public ExecutionLeaseRelease {
        reason = Objects.requireNonNull(reason, "reason");
        releasedAt = Objects.requireNonNull(releasedAt, "releasedAt");
    }
}
