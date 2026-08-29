package io.crewscope.application.identity;

import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.domain.identity.RegistrationMode;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Deployment and transport facts trusted by the local-registration use case. */
public record LocalAccountRegistrationContext(
        OrganizationId organizationId,
        RegistrationMode registrationMode,
        IdempotencyKey idempotencyKey,
        UUID correlationId,
        Optional<UUID> causationId) {

    public LocalAccountRegistrationContext {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        registrationMode = Objects.requireNonNull(registrationMode, "registrationMode");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        correlationId = requireUuid(correlationId, "correlationId");
        causationId = Objects.requireNonNull(causationId, "causationId")
                .map(value -> requireUuid(value, "causationId"));
    }

    private static UUID requireUuid(UUID value, String field) {
        UUID required = Objects.requireNonNull(value, field);
        if (AggregateId.NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(field + " must not use the nil UUID");
        }
        return required;
    }
}
