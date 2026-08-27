package io.crewscope.application.notification;

import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Stable newest-first position for the Team notification administration history. */
public record NotificationDeliveryCursor(
        UtcTimestamp updatedAt, NotificationDeliveryId deliveryId) {

    public NotificationDeliveryCursor {
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
    }
}
