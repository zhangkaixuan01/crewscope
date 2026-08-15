package io.crewscope.application.task;

import java.util.Objects;

/** Fenced command that advances only TaskExecution Version under a live Lease. */
public record LeaseExecutionCommand(
        LeaseCommandScope scope,
        long expectedExecutionVersion) {

    public LeaseExecutionCommand {
        Objects.requireNonNull(scope, "scope");
        if (expectedExecutionVersion < 0) {
            throw new IllegalArgumentException("expectedExecutionVersion must not be negative");
        }
    }
}
