package io.crewscope.application.operations;

import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Durable recovery receipt persisted atomically with the recovery fact and safe Audit event. */
public record OperationsRecoveryReceipt(
        OperationsRecoveryCommandId commandId,
        OrganizationId organizationId,
        OperationsRecoveryFingerprint fingerprint,
        OperationsRecoveryResult result) {

    public OperationsRecoveryReceipt {
        commandId = Objects.requireNonNull(commandId, "commandId");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        result = Objects.requireNonNull(result, "result");
    }

    public OperationsRecoveryResult replay(OperationsRecoveryFingerprint expected) {
        OperationsRecoveryFingerprint required = Objects.requireNonNull(expected, "expected");
        if (!fingerprint.equals(required)) {
            throw new IdempotencyConflictException(
                    commandId.value().toString(), fingerprint.value(), required.value());
        }
        return result;
    }
}
