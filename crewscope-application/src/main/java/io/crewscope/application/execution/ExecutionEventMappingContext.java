package io.crewscope.application.execution;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Trusted coordinates used to map one finite invoke or resume segment. */
public record ExecutionEventMappingContext(
        PlatformExecutionContext platformContext,
        UUID segmentId,
        Optional<UUID> causationDomainEventId) {

    public ExecutionEventMappingContext {
        platformContext = Objects.requireNonNull(platformContext, "platformContext");
        segmentId = requireUuid(segmentId, "segmentId");
        causationDomainEventId = Objects.requireNonNull(
                        causationDomainEventId, "causationDomainEventId")
                .map(value -> requireUuid(value, "causationDomainEventId"));
    }

    private static UUID requireUuid(UUID value, String field) {
        UUID required = Objects.requireNonNull(value, field);
        if (AggregateId.NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(field + " must not use the nil UUID");
        }
        return required;
    }
}
