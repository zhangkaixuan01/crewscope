package io.crewscope.application.notification;

import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationRedeliveryCommandId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** Fenced claim for one administrator-authorized notification redelivery schedule. */
public record NotificationRecoveryClaim(
        OrganizationId organizationId,
        UUID scheduleId,
        NotificationRedeliveryCommandId commandId,
        NotificationDeliveryId originalDeliveryId,
        long expectedDeliveryVersion,
        NotificationWorkerId workerId,
        long fencingToken,
        UtcTimestamp leaseExpiresAt) {

    public NotificationRecoveryClaim {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        scheduleId = Objects.requireNonNull(scheduleId, "scheduleId");
        commandId = Objects.requireNonNull(commandId, "commandId");
        originalDeliveryId = Objects.requireNonNull(originalDeliveryId, "originalDeliveryId");
        workerId = Objects.requireNonNull(workerId, "workerId");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (expectedDeliveryVersion < 0 || fencingToken < 1) {
            throw new IllegalArgumentException("Notification recovery claim counters are invalid");
        }
    }
}
