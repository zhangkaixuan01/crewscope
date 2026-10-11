package io.crewscope.application.workgraph;

import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemStatus;
import java.util.List;
import java.util.Objects;

/**
 * One row of the derived "currently blocked" aggregation (ADR-033): a WorkItem with at least one
 * BLOCKS in-edge whose src is still executing. {@code blockerCount} is the authoritative total;
 * {@code blockers} is a bounded summary window of at most {@link #MAX_BLOCKERS} entries.
 */
public record BlockedWorkItemRow(
        WorkItemId workItemId,
        String itemKey,
        String title,
        WorkItemStatus status,
        WorkProjectId projectId,
        UtcTimestamp blockedSince,
        int blockerCount,
        boolean crossesProject,
        List<Blocker> blockers) {

    /** Presentation window over the blockers; the count itself never truncates. */
    public static final int MAX_BLOCKERS = 20;

    /** One executing upstream blocker of the row's WorkItem. */
    public record Blocker(
            WorkItemId workItemId,
            String title,
            WorkItemStatus status,
            WorkProjectId projectId) {

        public Blocker {
            Objects.requireNonNull(workItemId, "workItemId");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(projectId, "projectId");
        }
    }

    public BlockedWorkItemRow {
        Objects.requireNonNull(workItemId, "workItemId");
        Objects.requireNonNull(itemKey, "itemKey");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(blockedSince, "blockedSince");
        Objects.requireNonNull(blockers, "blockers");
        blockers = List.copyOf(blockers);
        if (blockerCount < 0) {
            throw new IllegalArgumentException("blockerCount must not be negative");
        }
        if (blockers.size() > MAX_BLOCKERS) {
            throw new IllegalArgumentException(
                    "blockers must not exceed " + MAX_BLOCKERS + " rows");
        }
    }
}
