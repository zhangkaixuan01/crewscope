package io.crewscope.infrastructure.runtime;

import io.crewscope.domain.runtime.RuntimeWorkerStatus;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Derived Worker health snapshot; freshness never overwrites the explicit lifecycle status. */
public record RuntimeWorkerHealth(
        RuntimeWorkerIdentity identity,
        RuntimeWorkerStatus status,
        boolean heartbeatFresh,
        boolean claimable,
        int activeExecutions,
        int maxConcurrentExecutions,
        long heartbeatSequence,
        UtcTimestamp lastHeartbeatAt) {

    public RuntimeWorkerHealth {
        identity = Objects.requireNonNull(identity, "identity");
        status = Objects.requireNonNull(status, "status");
        lastHeartbeatAt = Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt");
    }
}
