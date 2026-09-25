package io.crewscope.application.workitem;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Batch assembler for {@link WorkItemExecutionSummary} (M9b-A06).
 *
 * <p>Implementations answer one page of rows with a fixed number of set-based queries keyed by the
 * page's ID set — never one query per row. Rows the caller did not ask for are never returned; a
 * requested ID missing from the result means the row left the tenant scope between the page read and
 * this assembly, which the caller may treat as "no summary".
 *
 * <p>An empty {@code projectId} assembles a page that spans projects — the WorkDesk's own shape; the
 * page's ID set is the scoping then, and the tenant predicates stay.
 */
public interface WorkItemSummaryRepository {

    int MAX_PAGE_IDS = 100;

    Map<WorkItemId, WorkItemExecutionSummary> summarize(
            OrganizationId organizationId,
            TeamId teamId,
            Optional<WorkProjectId> projectId,
            Collection<WorkItemId> workItemIds,
            UtcTimestamp observedAt);
}
