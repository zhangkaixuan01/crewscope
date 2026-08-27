package io.crewscope.domain.activity;

import io.crewscope.domain.shared.id.AggregateId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable Activity stream identity derived from the canonical DomainEvent identity. */
public record ActivityEventId(UUID value) implements AggregateId {

    private static final String NAMESPACE = "crewscope:activity-event:v1:";

    public ActivityEventId {
        value = AggregateId.requireValue(value, "ActivityEventId");
    }

    /**
     * Derives the same Activity identity during live projection and every historical rebuild.
     * Projection generation is deliberately excluded so Team and WorkItem queries can share it.
     */
    public static ActivityEventId fromDomainEvent(UUID domainEventId) {
        UUID required = AggregateId.requireValue(domainEventId, "ActivityEvent.domainEventId");
        return new ActivityEventId(UUID.nameUUIDFromBytes(
                (NAMESPACE + required).getBytes(StandardCharsets.UTF_8)));
    }

    public static ActivityEventId from(String value) {
        return new ActivityEventId(AggregateId.parseCanonical(value, "ActivityEventId"));
    }

    public ActivityEventId requireDomainEvent(UUID domainEventId) {
        if (!equals(fromDomainEvent(Objects.requireNonNull(domainEventId, "domainEventId")))) {
            throw new IllegalArgumentException(
                    "ActivityEventId must be derived from the canonical DomainEvent identity");
        }
        return this;
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
