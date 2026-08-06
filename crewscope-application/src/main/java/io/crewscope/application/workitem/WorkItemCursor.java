package io.crewscope.application.workitem;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Objects;

/** Stable keyset cursor ordered by update time and WorkItem ID in descending order. */
public record WorkItemCursor(UtcTimestamp updatedAt, WorkItemId id) {

    public WorkItemCursor {
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        id = Objects.requireNonNull(id, "id");
    }
}
