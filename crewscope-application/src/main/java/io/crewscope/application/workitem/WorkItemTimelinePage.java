package io.crewscope.application.workitem;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable page from the unified WorkItem event timeline. */
public record WorkItemTimelinePage(
    List<WorkItemTimelineEvent> items, Optional<WorkItemTimelineCursor> nextCursor) {

  public WorkItemTimelinePage {
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
  }
}
