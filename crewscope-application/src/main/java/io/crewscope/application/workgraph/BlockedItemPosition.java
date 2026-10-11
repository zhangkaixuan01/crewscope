package io.crewscope.application.workgraph;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Objects;

/**
 * Keyset position of one "currently blocked" row (ADR-033): the aggregation is ordered by
 * {@code (blockedSince DESC, workItemId DESC)} and this pair is the exclusive restart cursor the
 * encoded page token carries.
 */
public record BlockedItemPosition(UtcTimestamp blockedSince, WorkItemId workItemId) {

    public BlockedItemPosition {
        Objects.requireNonNull(blockedSince, "blockedSince");
        Objects.requireNonNull(workItemId, "workItemId");
    }
}
