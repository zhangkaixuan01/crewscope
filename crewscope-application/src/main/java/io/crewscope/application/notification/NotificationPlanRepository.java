package io.crewscope.application.notification;

import io.crewscope.domain.notification.NotificationDeduplicationKey;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationIntentId;
import io.crewscope.domain.notification.NotificationRedeliveryCommandId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Atomic persistence contract with unique DedupKey and redelivery Command ID constraints. */
public interface NotificationPlanRepository {

    Optional<NotificationPlan> findByDeduplicationKey(
            OrganizationId organizationId, NotificationDeduplicationKey key);

    Optional<NotificationPlan> findLatestByIntent(
            OrganizationId organizationId, NotificationIntentId intentId);

    Optional<NotificationPlan> findByDeliveryId(
            OrganizationId organizationId, NotificationDeliveryId deliveryId);

    Optional<NotificationRedeliveryRecord> findRedelivery(
            OrganizationId organizationId, NotificationRedeliveryCommandId commandId);

    /** Implementations persist both rows and resolve a concurrent unique-key race by reload. */
    NotificationPlan save(NotificationPlan plan);

    NotificationPlan update(NotificationPlan plan);

    /** Atomically invalidates a stale non-terminal plan and inserts its current replacement. */
    NotificationPlan replaceDrifted(
            NotificationPlan invalidatedPlan, NotificationPlan replacementPlan);

    /** Implementations atomically persist the plan and command receipt. */
    NotificationRedeliveryRecord saveRedelivery(NotificationRedeliveryRecord record);
}
