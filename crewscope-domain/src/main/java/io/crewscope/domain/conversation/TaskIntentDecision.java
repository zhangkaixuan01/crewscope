package io.crewscope.domain.conversation;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Immutable terminal decision captured for a confirmed, rejected or expired TaskIntent. */
public record TaskIntentDecision(
        TaskIntentStatus status,
        PrincipalId decidedByPrincipalId,
        UtcTimestamp decidedAt,
        Optional<String> reason) {

    public static final int MAX_REASON_LENGTH = 1_000;

    public TaskIntentDecision {
        status = Objects.requireNonNull(status, "status");
        if (!status.isTerminal()) {
            throw new DomainValidationException(
                    "taskIntentDecision.status", "must be a terminal TaskIntent status");
        }
        decidedByPrincipalId = Objects.requireNonNull(
                decidedByPrincipalId, "decidedByPrincipalId");
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
        reason = requireReason(status, reason);
    }

    public static TaskIntentDecision confirmed(
            PrincipalId actorId, UtcTimestamp occurredAt) {
        return new TaskIntentDecision(
                TaskIntentStatus.CONFIRMED,
                actorId,
                occurredAt,
                Optional.empty());
    }

    public static TaskIntentDecision rejected(
            PrincipalId actorId, String reason, UtcTimestamp occurredAt) {
        return new TaskIntentDecision(
                TaskIntentStatus.REJECTED,
                actorId,
                occurredAt,
                Optional.ofNullable(reason));
    }

    public static TaskIntentDecision expired(
            PrincipalId actorId, String reason, UtcTimestamp occurredAt) {
        return new TaskIntentDecision(
                TaskIntentStatus.EXPIRED,
                actorId,
                occurredAt,
                Optional.ofNullable(reason));
    }

    private static Optional<String> requireReason(
            TaskIntentStatus status, Optional<String> reason) {
        Optional<String> required = Objects.requireNonNull(reason, "reason");
        if (status == TaskIntentStatus.CONFIRMED && required.isPresent()) {
            throw new DomainValidationException(
                    "taskIntentDecision.reason", "must be empty for confirmation");
        }
        if (status != TaskIntentStatus.CONFIRMED && required.isEmpty()) {
            throw new DomainValidationException(
                    "taskIntentDecision.reason", "is required for rejection or expiration");
        }
        return required.map(value -> {
            if (value.isBlank()) {
                throw new DomainValidationException(
                        "taskIntentDecision.reason", "must not be blank");
            }
            String normalized = value.strip();
            if (normalized.length() > MAX_REASON_LENGTH) {
                throw new DomainValidationException(
                        "taskIntentDecision.reason",
                        "must contain at most " + MAX_REASON_LENGTH + " characters");
            }
            return normalized;
        });
    }
}
