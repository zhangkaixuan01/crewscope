package io.crewscope.application.quota;

import io.crewscope.domain.shared.id.OrganizationId;
import java.time.Instant;
import java.util.Objects;

/**
 * Transactional arbitration for the monthly task intake dimension (ClaimQuota form). The
 * authoritative count is the task table itself — no counter column can drift from the rows —
 * and a cancellation or failure intentionally does not give the slot back.
 */
public interface OrganizationTaskIntakeQuotaCounter {

    /**
     * Serializes competing intake checks with an organization-scoped advisory lock and counts
     * the org's tasks created inside the reporting-month window, all in the caller's
     * transaction.
     */
    Decision check(QuotaQuery query);

    enum Decision {
        AVAILABLE,
        INTAKE_LIMIT_REACHED
    }

    record QuotaQuery(
            OrganizationId organizationId,
            Instant monthWindowStart,
            Instant monthWindowEnd,
            int intakeLimit) {

        public QuotaQuery {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(monthWindowStart, "monthWindowStart");
            Objects.requireNonNull(monthWindowEnd, "monthWindowEnd");
            if (!monthWindowStart.isBefore(monthWindowEnd)) {
                throw new IllegalArgumentException("the month window must be non-empty");
            }
            if (intakeLimit < 1) {
                throw new IllegalArgumentException("the intake limit must be positive");
            }
        }
    }
}
