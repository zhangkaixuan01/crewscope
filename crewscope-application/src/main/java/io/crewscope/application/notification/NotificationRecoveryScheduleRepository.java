package io.crewscope.application.notification;

import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Durable bridge from audited Operations recovery schedules to new redelivery Plans. */
public interface NotificationRecoveryScheduleRepository {

    List<OrganizationId> findOrganizations(UtcTimestamp now, int limit);

    Optional<NotificationRecoveryClaim> claim(
            OrganizationId organizationId,
            NotificationWorkerId workerId,
            UtcTimestamp now,
            Duration leaseDuration);

    void complete(
            NotificationRecoveryClaim claim,
            NotificationDeliveryId replacementDeliveryId,
            UtcTimestamp now);
}
