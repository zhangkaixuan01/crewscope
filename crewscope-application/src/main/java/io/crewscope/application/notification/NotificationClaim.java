package io.crewscope.application.notification;

import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Immutable fencing coordinates returned only after a database claim has committed. */
public record NotificationClaim(
        NotificationDeliveryId deliveryId,
        NotificationWorkerId workerId,
        long fencingToken,
        long deliveryVersion,
        int reconciliationCount,
        UtcTimestamp leaseExpiresAt) {

    public NotificationClaim {
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        workerId = Objects.requireNonNull(workerId, "workerId");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (fencingToken < 1 || deliveryVersion < 1 || reconciliationCount < 0) {
            throw new IllegalArgumentException("Notification claim counters are invalid");
        }
    }
}
