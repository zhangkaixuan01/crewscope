package io.crewscope.domain.task.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Safe audit payload for one accepted fenced Worker command. */
public record WorkerTaskCommandAccepted(
        UUID taskExecutionId,
        int attempt,
        UUID executionLeaseId,
        String operation,
        Optional<Long> taskExecutionVersion,
        Optional<Long> leaseVersion,
        Optional<String> safeSummary,
        Optional<Integer> progressPercent,
        Optional<String> failureClass,
        Optional<String> failureCode) implements DomainEvent {

    private static final Pattern OPERATION = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern FAILURE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public WorkerTaskCommandAccepted {
        taskExecutionId = AggregateId.requireValue(taskExecutionId, "taskExecutionId");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        executionLeaseId = AggregateId.requireValue(executionLeaseId, "executionLeaseId");
        operation = requirePattern(operation, OPERATION, "operation");
        taskExecutionVersion = requireVersion(taskExecutionVersion, "taskExecutionVersion");
        leaseVersion = requireVersion(leaseVersion, "leaseVersion");
        safeSummary = Objects.requireNonNull(safeSummary, "safeSummary")
                .map(value -> requireText(value, "safeSummary", 1_000));
        progressPercent = Objects.requireNonNull(progressPercent, "progressPercent");
        progressPercent.ifPresent(value -> {
            if (value < 0 || value > 100) {
                throw new IllegalArgumentException("progressPercent must be between 0 and 100");
            }
        });
        failureClass = Objects.requireNonNull(failureClass, "failureClass")
                .map(value -> requirePattern(value, FAILURE, "failureClass"));
        failureCode = Objects.requireNonNull(failureCode, "failureCode")
                .map(value -> requirePattern(value, FAILURE, "failureCode"));
        if (operation.equals("PROGRESS") != safeSummary.isPresent()
                || (!operation.equals("PROGRESS") && progressPercent.isPresent())) {
            throw new IllegalArgumentException(
                    "safeSummary must exist exactly for PROGRESS and percent only for PROGRESS");
        }
        if (operation.equals("FAIL") != failureClass.isPresent()
                || failureClass.isPresent() != failureCode.isPresent()) {
            throw new IllegalArgumentException(
                    "failure fields must exist exactly for a FAIL command");
        }
    }

    private static Optional<Long> requireVersion(Optional<Long> value, String field) {
        return Objects.requireNonNull(value, field).map(version -> {
            if (version < 0) {
                throw new IllegalArgumentException(field + " must not be negative");
            }
            return version;
        });
    }

    private static String requirePattern(String value, Pattern pattern, String field) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " has an invalid stable value");
        }
        return normalized;
    }

    private static String requireText(String value, String field, int maximumLength) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty()
                || normalized.length() > maximumLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must contain printable bounded text");
        }
        return normalized;
    }
}
