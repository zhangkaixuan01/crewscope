package io.crewscope.infrastructure.runtime;

import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import java.util.Objects;

/** Stable database identity resolved for the current JVM Worker deployment. */
public record RuntimeWorkerIdentity(
        ExecutionRuntimeId runtimeId,
        RuntimeWorkerId workerId,
        String stableKey,
        RuntimeProfile profile) {

    public RuntimeWorkerIdentity {
        runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
        workerId = Objects.requireNonNull(workerId, "workerId");
        if (stableKey == null || stableKey.isBlank()) {
            throw new IllegalArgumentException("stableKey must not be blank");
        }
        stableKey = stableKey.strip();
        profile = Objects.requireNonNull(profile, "profile");
    }
}
