package io.crewscope.application.notification;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Durable Notification queue Port with PostgreSQL locking and fenced outcome writes. */
public interface NotificationDispatchRepository {

    List<OrganizationId> findExecutionOrganizations(UtcTimestamp now, int limit);

    /** Locks and transitions one due READY/RETRY_WAIT delivery to a committed RUNNING claim. */
    Optional<ClaimedNotification> claimExecution(
            OrganizationId organizationId,
            NotificationWorkerId workerId,
            UtcTimestamp now,
            Duration leaseDuration);

    List<OrganizationId> findReconciliationOrganizations(
            UtcTimestamp now, Duration retryDelay, int limit);

    /** Claims UNKNOWN or takes over an expired RUNNING/RECONCILING delivery for query-only work. */
    Optional<ClaimedNotification> claimReconciliation(
            OrganizationId organizationId,
            NotificationWorkerId workerId,
            UtcTimestamp now,
            Duration leaseDuration,
            Duration retryDelay);

    /**
     * Commits an outcome only while the exact token, expected version and lease remain current.
     * A stale Worker receives an optimistic conflict and performs no write.
     */
    NotificationPlan updateClaimed(
            OrganizationId organizationId,
            NotificationClaim claim,
            NotificationPlan outcome,
            UtcTimestamp authoritativeNow);
}
