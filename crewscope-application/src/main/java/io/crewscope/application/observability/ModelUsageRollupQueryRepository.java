package io.crewscope.application.observability;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Collection;
import java.util.List;

/**
 * Read side of the {@code model_usage_monthly_rollup} projection (M10-F03). Read-only on
 * purpose: writes flow exclusively through the rollup writer behind the consumer and the
 * rebuild endpoint.
 */
public interface ModelUsageRollupQueryRepository {

    /**
     * Reporting months that carry rollup rows, newest first. {@code after} is an exclusive
     * month keyset cursor ('YYYY-MM'); implementations must treat it as opaque text so the
     * lexicographic month order stays the projection's own invariant.
     */
    List<String> findUsageMonths(
            OrganizationId organizationId, TeamId teamId, String after, int limit);

    /** Role×currency aggregates of the given months (rows summed across the remaining grain). */
    List<ModelUsageMonthRoleCurrencyRow> findMonthAggregates(
            OrganizationId organizationId, TeamId teamId, Collection<String> usageMonths);

    /** Full-grain detail rows of one month, ordered by role, model, currency and attempt. */
    List<ModelUsageMonthDetailRow> findMonthDetail(
            OrganizationId organizationId, TeamId teamId, String usageMonth);
}
