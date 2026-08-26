package io.crewscope.domain.notification;

import io.crewscope.domain.shared.id.AggregateId;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stable identifier of one immutable notification terminal receipt. */
public record NotificationReceiptId(UUID value) implements AggregateId {
    public NotificationReceiptId {
        value = AggregateId.requireValue(value, "NotificationReceiptId");
    }

    public static NotificationReceiptId generate() {
        return new NotificationReceiptId(AggregateId.generateValue());
    }

    /** Stable terminal identity makes a database-uncertain outcome safe to replay. */
    public static NotificationReceiptId fromDelivery(NotificationDeliveryId deliveryId) {
        String name = "notification-receipt-v1:" + deliveryId;
        return new NotificationReceiptId(
                UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    }
}
