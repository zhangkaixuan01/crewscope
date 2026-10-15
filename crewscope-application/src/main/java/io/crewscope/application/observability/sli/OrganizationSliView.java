package io.crewscope.application.observability.sli;

import java.time.Instant;
import java.util.List;

/**
 * Response shape of GET /api/v1/organizations/{orgId}/sli (M12-I03). Nulls are
 * meaningful — a null value with availability "no_data" says "denominator empty",
 * a null errorBudget says "this window carries no budget" — the same convention as
 * the quota usage view.
 */
public record OrganizationSliView(
        String organizationId, Instant generatedAt, String reportingZone, List<MetricView> metrics) {

    /** One of the six ADR-037 §5 indicators. */
    public record MetricView(String id, String objective, String dataSource, List<WindowView> windows) {}

    /**
     * One reading: window names the semantics (process_lifetime, rolling_28d,
     * current_day, snapshot), component splits a metric into legs (model_side,
     * app_overhead, outbox_delivery, projection_replay), availability is ok /
     * degraded / no_data.
     */
    public record WindowView(
            String window,
            String scope,
            String component,
            String unit,
            Double value,
            Long sampleCount,
            String availability,
            ErrorBudgetView errorBudget,
            String note) {}

    /** Burn-down of one failure allowance; status "exhausted" once consumedRatio >= 1. */
    public record ErrorBudgetView(
            String basis,
            double allowedFailureRatio,
            Double observedFailureRatio,
            Double consumedRatio,
            String status) {}
}
