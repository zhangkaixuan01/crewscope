package io.crewscope.application.task;

import io.crewscope.domain.task.ExecutionLeaseReleaseReason;
import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import java.util.Objects;
import java.util.Optional;

/** Explicit Worker release with the minimum state-specific terminal facts. */
public record LeaseReleaseCommand(
        LeaseTransitionCommand executionCommand,
        ExecutionLeaseReleaseReason reason,
        Optional<TaskExecutionWaitReason> waitReason,
        Optional<TaskExecutionFailure> failure) {

    public LeaseReleaseCommand {
        Objects.requireNonNull(executionCommand, "executionCommand");
        Objects.requireNonNull(reason, "reason");
        waitReason = Objects.requireNonNull(waitReason, "waitReason");
        failure = Objects.requireNonNull(failure, "failure");
        if (reason == ExecutionLeaseReleaseReason.EXPIRED) {
            throw new IllegalArgumentException("EXPIRED is reserved for ExecutionLeaseSweeper");
        }
        if ((reason == ExecutionLeaseReleaseReason.WAITING) != waitReason.isPresent()) {
            throw new IllegalArgumentException("waitReason must exist only for WAITING release");
        }
        if ((reason == ExecutionLeaseReleaseReason.FAILED) != failure.isPresent()) {
            throw new IllegalArgumentException("failure must exist only for FAILED release");
        }
        waitReason.ifPresent(value -> {
            if (value == TaskExecutionWaitReason.RUNTIME) {
                throw new IllegalArgumentException("RUNTIME wait is entered before Claim");
            }
        });
    }

    public static LeaseReleaseCommand simple(
            LeaseTransitionCommand command, ExecutionLeaseReleaseReason reason) {
        return new LeaseReleaseCommand(command, reason, Optional.empty(), Optional.empty());
    }
}
