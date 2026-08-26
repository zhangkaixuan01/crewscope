package io.crewscope.application.inbox;

import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.Objects;
import java.util.Set;

/** Exact reviewed DomainEvent coordinate and the fields used by the Inbox Projector. */
public record InboxEventTypeDefinition(
        EventType eventType,
        SchemaVersion schemaVersion,
        InboxProjectionOperation operation,
        Set<String> requiredPayloadFields) {

    public InboxEventTypeDefinition {
        eventType = Objects.requireNonNull(eventType, "eventType");
        schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        operation = Objects.requireNonNull(operation, "operation");
        requiredPayloadFields = Set.copyOf(
                Objects.requireNonNull(requiredPayloadFields, "requiredPayloadFields"));
        if (requiredPayloadFields.stream().anyMatch(field -> field == null || field.isBlank())) {
            throw new IllegalArgumentException("Inbox payload field names must not be blank");
        }
    }
}
