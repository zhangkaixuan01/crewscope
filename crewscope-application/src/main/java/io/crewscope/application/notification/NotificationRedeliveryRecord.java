package io.crewscope.application.notification;

import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationRedeliveryCommandId;
import java.util.Objects;

/** Durable command receipt that makes a repeated redelivery command return the same send. */
public record NotificationRedeliveryRecord(
        NotificationRedeliveryCommandId commandId,
        NotificationDeliveryId originalDeliveryId,
        NotificationPlan plan) {

    public NotificationRedeliveryRecord {
        commandId = Objects.requireNonNull(commandId, "commandId");
        originalDeliveryId = Objects.requireNonNull(originalDeliveryId, "originalDeliveryId");
        plan = Objects.requireNonNull(plan, "plan");
        if (plan.action().redeliveryOf().filter(originalDeliveryId::equals).isEmpty()
                || plan.delivery().redeliveryOf().filter(originalDeliveryId::equals).isEmpty()) {
            throw new IllegalArgumentException("Redelivery record must point to its original delivery");
        }
    }
}
