package io.crewscope.application.observability;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.time.Instant;

/**
 * Direct quality counters over the task execution and review fact tables (M10-F03). The
 * contract deliberately builds no projection here: the underlying tables are the durable
 * facts and the window is always a single month.
 */
public interface TaskQualityStatisticsRepository {

    /** Counters for executions created inside {@code [windowStart, windowEnd)}. */
    TaskQualityStatistics findForWindow(
            OrganizationId organizationId,
            TeamId teamId,
            Instant windowStart,
            Instant windowEnd);
}
