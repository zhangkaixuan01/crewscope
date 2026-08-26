package io.crewscope.application.operations;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Safe command result shared by first execution and exact idempotent replay. */
public record OperationsRecoveryResult(
        OperationsRecoveryAction action,
        String targetReferenceHash,
        OperationsRecoveryStatus status,
        UtcTimestamp acceptedAt) {

    public OperationsRecoveryResult {
        action = Objects.requireNonNull(action, "action");
        targetReferenceHash = Objects.requireNonNull(targetReferenceHash, "targetReferenceHash");
        status = Objects.requireNonNull(status, "status");
        acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (!targetReferenceHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("targetReferenceHash must be a lowercase SHA-256");
        }
    }
}
