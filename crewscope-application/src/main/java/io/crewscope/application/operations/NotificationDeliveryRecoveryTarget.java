package io.crewscope.application.operations;

import io.crewscope.domain.notification.NotificationDeliveryId;
import java.util.List;
import java.util.Objects;

/** Exact failed-final Notification Delivery selected for an explicit new attempt. */
public record NotificationDeliveryRecoveryTarget(
        NotificationDeliveryId deliveryId,
        long expectedVersion) implements OperationsRecoveryTarget {

    public NotificationDeliveryRecoveryTarget {
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }

    @Override
    public OperationsRecoveryAction action() {
        return OperationsRecoveryAction.RETRY_NOTIFICATION_DELIVERY;
    }

    @Override
    public List<String> fingerprintCoordinates() {
        return List.of(action().name(), deliveryId.value().toString(), Long.toString(expectedVersion));
    }

    @Override
    public String confirmationToken() {
        return deliveryId.value() + ":" + expectedVersion;
    }
}
