package io.crewscope.application.workitem;

import io.crewscope.domain.workitem.WorkItem;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable keyset page returned by the WorkItem Repository Port. */
public record WorkItemPage(List<WorkItem> items, Optional<WorkItemCursor> nextCursor) {

    public WorkItemPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
