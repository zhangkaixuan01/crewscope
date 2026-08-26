package io.crewscope.application.notification;

import io.crewscope.domain.notification.NotificationDelivery;
import io.crewscope.domain.notification.NotificationPlannedAction;
import java.util.Objects;

/** Atomically persisted notification action and its one delivery. */
public record NotificationPlan(
        NotificationPlannedAction action, NotificationDelivery delivery) {

    public NotificationPlan {
        action = Objects.requireNonNull(action, "action");
        delivery = Objects.requireNonNull(delivery, "delivery");
        if (!action.id().equals(delivery.actionId())
                || !action.digest().equals(delivery.actionDigest())
                || !action.authority().deduplicationKey().equals(delivery.deduplicationKey())
                || !action.redeliveryOf().equals(delivery.redeliveryOf())) {
            throw new IllegalArgumentException("Notification plan action and delivery must match");
        }
    }
}
