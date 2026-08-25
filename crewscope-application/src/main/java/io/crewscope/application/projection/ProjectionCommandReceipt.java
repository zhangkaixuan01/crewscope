package io.crewscope.application.projection;

import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Durable projection command receipt persisted atomically with lifecycle and Audit facts. */
public record ProjectionCommandReceipt(
        ProjectionAdministrationCommandId commandId,
        OrganizationId organizationId,
        ProjectionCommandFingerprint fingerprint,
        ProjectionAdministrationResult result) {

    public ProjectionCommandReceipt {
        commandId = Objects.requireNonNull(commandId, "commandId");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        result = Objects.requireNonNull(result, "result");
        if (!organizationId.equals(result.organizationId())) {
            throw new IllegalArgumentException("Projection command receipt has mixed Organization scope");
        }
    }

    public ProjectionAdministrationResult replay(ProjectionCommandFingerprint expected) {
        ProjectionCommandFingerprint required = Objects.requireNonNull(expected, "expected");
        if (!fingerprint.equals(required)) {
            throw new IdempotencyConflictException(
                    commandId.value().toString(), fingerprint.value(), required.value());
        }
        return result;
    }
}
