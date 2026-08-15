package io.crewscope.application.task;

import java.util.Objects;

/** Fenced Heartbeat command; it advances only Lease Version. */
public record LeaseHeartbeatCommand(LeaseCommandScope scope, long expectedLeaseVersion) {

    public LeaseHeartbeatCommand {
        Objects.requireNonNull(scope, "scope");
        if (expectedLeaseVersion < 0) {
            throw new IllegalArgumentException("expectedLeaseVersion must not be negative");
        }
    }
}
