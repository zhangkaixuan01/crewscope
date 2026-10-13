package io.crewscope.application.organization;

import io.crewscope.application.command.IdempotencyKey;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Trusted identity plus retry and tracing metadata for one organization-level command. */
public record OrganizationCommandContext(
        OrganizationAccessContext access,
        IdempotencyKey idempotencyKey,
        UUID correlationId,
        Optional<UUID> causationId) {

    public OrganizationCommandContext {
        access = Objects.requireNonNull(access, "access");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        causationId = Objects.requireNonNull(causationId, "causationId");
    }
}
