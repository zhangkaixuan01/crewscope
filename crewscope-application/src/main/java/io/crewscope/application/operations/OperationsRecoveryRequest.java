package io.crewscope.application.operations;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Sanitized persistence request; the user-entered confirmation phrase is intentionally absent. */
public record OperationsRecoveryRequest(
        OperationsRecoveryCommandId commandId,
        OrganizationId organizationId,
        OperationsRecoveryTarget target,
        PrincipalId actorId,
        OperationsRecoveryFingerprint fingerprint,
        UtcTimestamp occurredAt) {

    public OperationsRecoveryRequest {
        commandId = Objects.requireNonNull(commandId, "commandId");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        target = Objects.requireNonNull(target, "target");
        actorId = Objects.requireNonNull(actorId, "actorId");
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
