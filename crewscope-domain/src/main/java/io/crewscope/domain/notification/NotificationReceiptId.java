package io.crewscope.domain.notification;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identifier of one immutable notification terminal receipt. */
public record NotificationReceiptId(UUID value) implements AggregateId {
    public NotificationReceiptId {
        value = AggregateId.requireValue(value, "NotificationReceiptId");
    }

    public static NotificationReceiptId generate() {
        return new NotificationReceiptId(AggregateId.generateValue());
    }
}
