package io.crewscope.application.notification;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationRedeliveryCommandId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Strong-versioned explicit request for a new send after one final failure. */
public record RedeliverNotificationCommand(
        NotificationRedeliveryCommandId commandId,
        OrganizationId organizationId,
        NotificationDeliveryId originalDeliveryId,
        long expectedDeliveryVersion,
        Principal actor) {

    public RedeliverNotificationCommand {
        commandId = Objects.requireNonNull(commandId, "commandId");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        originalDeliveryId = Objects.requireNonNull(originalDeliveryId, "originalDeliveryId");
        actor = Objects.requireNonNull(actor, "actor");
        if (expectedDeliveryVersion < 0) {
            throw new IllegalArgumentException("expectedDeliveryVersion must not be negative");
        }
    }
}
