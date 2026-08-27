package io.crewscope.application.notification;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationPreference;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.util.List;
import java.util.Optional;

/** PostgreSQL-facing administration reads and the strong-versioned preference mutation. */
public interface NotificationAdministrationRepository {

    Optional<NotificationPreference> findPreference(
            OrganizationId organizationId, TeamId teamId, TeamMemberId memberId);

    NotificationPreference savePreference(
            OrganizationId organizationId,
            TeamId teamId,
            NotificationPreference preference,
            long expectedVersion,
            Principal actor,
            UtcTimestamp now);

    List<NotificationTemplateView> listTemplates();

    NotificationDeliveryPage findDeliveries(
            OrganizationId organizationId,
            TeamId teamId,
            NotificationDeliveryFilter filter,
            Optional<NotificationDeliveryCursor> after,
            int limit);

    Optional<NotificationDeliveryView> findDelivery(
            OrganizationId organizationId,
            TeamId teamId,
            NotificationDeliveryId deliveryId);
}
