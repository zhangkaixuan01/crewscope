package io.crewscope.domain.task.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Safe audit payload for one accepted member Pause, Resume, Cancel or Retry command. */
public record MemberTaskCommandAccepted(
        UUID taskId,
        UUID targetExecutionId,
        int targetAttempt,
        String operation,
        String taskStatus,
        String executionStatus,
        Optional<UUID> successorExecutionId,
        Optional<Integer> successorAttempt,
        Optional<UUID> successorPolicySnapshotId,
        Optional<String> successorPolicySnapshotHash,
        Optional<String> successorExecutionScope,
        Optional<Long> successorConfigurationRevision,
        Optional<String> successorConfigurationHash) implements DomainEvent {

    private static final Set<String> OPERATIONS = Set.of("PAUSE", "RESUME", "CANCEL", "RETRY");

    public MemberTaskCommandAccepted {
        taskId = Objects.requireNonNull(taskId, "taskId");
        targetExecutionId = Objects.requireNonNull(targetExecutionId, "targetExecutionId");
        if (targetAttempt < 1) {
            throw new DomainValidationException("memberTaskCommand.targetAttempt", "must be positive");
        }
        operation = Objects.requireNonNull(operation, "operation");
        if (!OPERATIONS.contains(operation)) {
            throw new DomainValidationException("memberTaskCommand.operation", "is not supported");
        }
        taskStatus = requireText(taskStatus, "taskStatus");
        executionStatus = requireText(executionStatus, "executionStatus");
        successorExecutionId = Objects.requireNonNull(successorExecutionId, "successorExecutionId");
        successorAttempt = Objects.requireNonNull(successorAttempt, "successorAttempt");
        successorPolicySnapshotId = Objects.requireNonNull(
                successorPolicySnapshotId, "successorPolicySnapshotId");
        successorPolicySnapshotHash = Objects.requireNonNull(
                successorPolicySnapshotHash, "successorPolicySnapshotHash");
        successorExecutionScope = Objects.requireNonNull(
                successorExecutionScope, "successorExecutionScope");
        successorConfigurationRevision = Objects.requireNonNull(
                successorConfigurationRevision, "successorConfigurationRevision");
        successorConfigurationHash = Objects.requireNonNull(
                successorConfigurationHash, "successorConfigurationHash");
        if (successorExecutionId.isPresent() != successorAttempt.isPresent()
                || (operation.equals("RETRY") != successorExecutionId.isPresent())) {
            throw new DomainValidationException(
                    "memberTaskCommand.successorExecutionId",
                    "must exist exactly for Retry with successorAttempt");
        }
        long policyFields = java.util.stream.Stream.of(
                        successorPolicySnapshotId, successorPolicySnapshotHash)
                .filter(Optional::isPresent)
                .count();
        long configurationFields = java.util.stream.Stream.of(
                        successorExecutionScope,
                        successorConfigurationRevision,
                        successorConfigurationHash)
                .filter(Optional::isPresent)
                .count();
        if ((policyFields != 0 && policyFields != 2)
                || (configurationFields != 0 && configurationFields != 3)
                || (configurationFields > 0 && policyFields == 0)) {
            throw new DomainValidationException(
                    "memberTaskCommand.successorPolicySnapshotId",
                    "configuration audit fields must be present together");
        }
    }

    private static String requireText(String value, String field) {
        String required = Objects.requireNonNull(value, field).strip();
        if (required.isEmpty() || required.length() > 64) {
            throw new DomainValidationException("memberTaskCommand." + field, "has an invalid format");
        }
        return required;
    }
}
