package io.crewscope.application.organization.governance;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;

/**
 * Deletion face of the audit retention sweep (S01 freeze 3.4). The implementation deletes
 * expired non-SECURITY audit rows and appends the sweep's own audit fact in the same
 * transaction, so the self-evidence never lags the deletion. SECURITY-category rows are the
 * hard exception: governance self-facts are retained regardless of the retention window.
 */
public interface AuditRetentionGateway {

    /**
     * Deletes at most {@code limit} audit rows of the organization strictly older than
     * {@code cutoff}, excluding SECURITY-category rows, and appends the sweep audit fact
     * atomically. Returns the number of deleted rows.
     */
    long deleteExpiredBefore(OrganizationId organizationId, UtcTimestamp cutoff, int limit);

    /** The retention cutoff implied by a retention window: now minus the window. */
    static UtcTimestamp cutoffAt(UtcTimestamp now, Duration retentionWindow) {
        return UtcTimestamp.from(now.value().minus(retentionWindow));
    }
}
