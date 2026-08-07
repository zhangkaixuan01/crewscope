package io.crewscope.application.command;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.UUID;

/** Durable acknowledgement linking an accepted command to its committed domain fact. */
public record CommandReceipt(
        UUID commandId,
        UUID domainEventId,
        long committedVersion,
        UUID correlationId) {

    public CommandReceipt {
        commandId = requireUuid(commandId, "commandId");
        domainEventId = requireUuid(domainEventId, "domainEventId");
        if (committedVersion < 0) {
            throw new IllegalArgumentException("committedVersion must not be negative");
        }
        correlationId = requireUuid(correlationId, "correlationId");
    }

    private static UUID requireUuid(UUID value, String name) {
        UUID required = Objects.requireNonNull(value, name);
        if (AggregateId.NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(name + " must not use the nil UUID");
        }
        return required;
    }
}
