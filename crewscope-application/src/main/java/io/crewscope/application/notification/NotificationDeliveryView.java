package io.crewscope.application.notification;

import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationDeliveryStatus;
import io.crewscope.domain.notification.NotificationFailureCode;
import io.crewscope.domain.notification.NotificationTemplateRef;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;
import java.util.Optional;

/** Browser-safe delivery history row without variables, authorization snapshots or Provider body. */
public record NotificationDeliveryView(
        OrganizationId organizationId,
        TeamId teamId,
        NotificationDeliveryId deliveryId,
        TeamMemberId recipientMemberId,
        InboxItemType itemType,
        NotificationTemplateRef template,
        ProviderBindingId providerBindingId,
        NotificationDeliveryStatus status,
        int attemptCount,
        Optional<NotificationFailureCode> failureCode,
        Optional<String> evidenceCode,
        Optional<NotificationDeliveryId> redeliveryOf,
        UtcTimestamp createdAt,
        UtcTimestamp updatedAt,
        long version) {

    public NotificationDeliveryView {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        recipientMemberId = Objects.requireNonNull(recipientMemberId, "recipientMemberId");
        itemType = Objects.requireNonNull(itemType, "itemType");
        template = Objects.requireNonNull(template, "template");
        providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
        status = Objects.requireNonNull(status, "status");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        evidenceCode = Objects.requireNonNull(evidenceCode, "evidenceCode");
        redeliveryOf = Objects.requireNonNull(redeliveryOf, "redeliveryOf");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (attemptCount < 0 || version < 0 || status.terminal() != evidenceCode.isPresent()) {
            throw new IllegalArgumentException("Notification delivery view has an invalid shape");
        }
    }

    public NotificationDeliveryCursor cursor() {
        return new NotificationDeliveryCursor(updatedAt, deliveryId);
    }
}
