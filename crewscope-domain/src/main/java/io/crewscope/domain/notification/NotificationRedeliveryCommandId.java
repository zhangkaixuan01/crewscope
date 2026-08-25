package io.crewscope.domain.notification;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Idempotency identity of one explicit redelivery request. */
public record NotificationRedeliveryCommandId(UUID value) implements AggregateId {
    public NotificationRedeliveryCommandId {
        value = AggregateId.requireValue(value, "NotificationRedeliveryCommandId");
    }

    public static NotificationRedeliveryCommandId generate() {
        return new NotificationRedeliveryCommandId(AggregateId.generateValue());
    }
}
