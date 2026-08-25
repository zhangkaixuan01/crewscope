package io.crewscope.domain.notification;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Versioned mapping from one CrewScope member to a provider recipient. */
public record NotificationRecipientMappingId(UUID value) implements AggregateId {
    public NotificationRecipientMappingId {
        value = AggregateId.requireValue(value, "NotificationRecipientMappingId");
    }

    public static NotificationRecipientMappingId generate() {
        return new NotificationRecipientMappingId(AggregateId.generateValue());
    }
}
