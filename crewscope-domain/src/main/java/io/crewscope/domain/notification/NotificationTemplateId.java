package io.crewscope.domain.notification;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identifier of a server-controlled notification template family. */
public record NotificationTemplateId(UUID value) implements AggregateId {

    public NotificationTemplateId {
        value = AggregateId.requireValue(value, "NotificationTemplateId");
    }

    public static NotificationTemplateId generate() {
        return new NotificationTemplateId(AggregateId.generateValue());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
