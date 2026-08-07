package io.crewscope.application.command;

import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** Facts atomically reserved before a command is allowed to create side effects. */
public record CommandReservationRequest(
        OrganizationId organizationId,
        IdempotencyKey idempotencyKey,
        String commandType,
        CommandRequestHash requestHash,
        UUID commandId,
        UUID correlationId,
        UtcTimestamp requestedAt) {

    public CommandReservationRequest {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (commandType == null || commandType.isBlank() || commandType.strip().length() > 100) {
            throw new IllegalArgumentException("commandType must contain between 1 and 100 characters");
        }
        commandType = commandType.strip();
        requestHash = Objects.requireNonNull(requestHash, "requestHash");
        commandId = requireUuid(commandId, "commandId");
        correlationId = requireUuid(correlationId, "correlationId");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }

    private static UUID requireUuid(UUID value, String name) {
        UUID required = Objects.requireNonNull(value, name);
        if (AggregateId.NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(name + " must not use the nil UUID");
        }
        return required;
    }
}
