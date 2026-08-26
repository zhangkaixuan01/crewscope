package io.crewscope.application.notification;

import java.util.Objects;

/** A persisted Notification Plan paired with its exact committed Worker claim. */
public record ClaimedNotification(NotificationPlan plan, NotificationClaim claim) {

    public ClaimedNotification {
        plan = Objects.requireNonNull(plan, "plan");
        claim = Objects.requireNonNull(claim, "claim");
        if (!plan.delivery().id().equals(claim.deliveryId())
                || plan.delivery().version() != claim.deliveryVersion()) {
            throw new IllegalArgumentException("Notification plan and claim must match");
        }
    }
}
