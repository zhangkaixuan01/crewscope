package io.crewscope.application.task;

import java.util.Objects;

/** Fenced command that atomically advances TaskExecution and ExecutionLease versions. */
public record LeaseTransitionCommand(
        LeaseCommandScope scope,
        long expectedExecutionVersion,
        long expectedLeaseVersion) {

    public LeaseTransitionCommand {
        Objects.requireNonNull(scope, "scope");
        if (expectedExecutionVersion < 0 || expectedLeaseVersion < 0) {
            throw new IllegalArgumentException("expected versions must not be negative");
        }
    }
}
