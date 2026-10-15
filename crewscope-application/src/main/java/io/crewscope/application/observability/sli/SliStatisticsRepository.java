package io.crewscope.application.observability.sli;

import io.crewscope.domain.shared.id.OrganizationId;
import java.time.Instant;
import java.util.Objects;

/**
 * Organization-level read-only facts for the M12-I03 SLI surface. Deliberately its own
 * port rather than a team-predicated reuse: the A02 admission services and this read-only
 * observation surface must not share one service class (ADR-037 implementation
 * constraint), and team granularity adds a predicate this surface does not have.
 */
public interface SliStatisticsRepository {

    /** Terminal-outcome counts of task executions in {@code [windowStart, windowEnd)}. */
    SliExecutionStatistics executionStatistics(
            OrganizationId organizationId, Instant windowStart, Instant windowEnd);

    /** Terminal-outcome counts of notification deliveries in {@code [windowStart, windowEnd)}. */
    SliNotificationStatistics notificationStatistics(
            OrganizationId organizationId, Instant windowStart, Instant windowEnd);

    /** Current queueing depth and age facts (no window semantics). */
    SliQueueingSnapshot queueingSnapshot(OrganizationId organizationId);

    /** Guards one window query against null coordinates (both bounds are required). */
    static void requireWindow(Instant windowStart, Instant windowEnd) {
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        if (!windowStart.isBefore(windowEnd)) {
            throw new IllegalArgumentException("window start must precede window end");
        }
    }
}
