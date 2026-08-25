package io.crewscope.domain.notification;

import io.crewscope.domain.shared.id.AggregateId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable identity of one explicitly authorized provider send. */
public record NotificationDeliveryId(UUID value) implements AggregateId {
    public NotificationDeliveryId {
        value = AggregateId.requireValue(value, "NotificationDeliveryId");
    }

    public static NotificationDeliveryId fromDeduplicationKey(NotificationDeduplicationKey key) {
        return new NotificationDeliveryId(UUID.nameUUIDFromBytes(
                ("notification-delivery-v1:" + Objects.requireNonNull(key, "key"))
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
