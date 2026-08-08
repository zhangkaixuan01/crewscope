package io.crewscope.domain.workitem.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemStatus;
import java.util.Objects;
import java.util.UUID;

/** Version 1 business payload emitted after a WorkItem state transition. */
public record WorkItemStatusChanged(
    UUID projectId, String itemKey, WorkItemStatus previousStatus, WorkItemStatus status)
    implements DomainEvent {

  public WorkItemStatusChanged {
    projectId = AggregateId.requireValue(projectId, "WorkItemStatusChanged.projectId");
    itemKey = new WorkItemKey(itemKey).value();
    previousStatus = Objects.requireNonNull(previousStatus, "previousStatus");
    status = Objects.requireNonNull(status, "status");
    if (previousStatus == status) {
      throw new IllegalArgumentException("status must differ from previousStatus");
    }
  }

  public static WorkItemStatusChanged from(WorkItem before, WorkItem after) {
    WorkItem previous = Objects.requireNonNull(before, "before");
    WorkItem current = Objects.requireNonNull(after, "after");
    if (!previous.id().equals(current.id()) || !previous.scope().equals(current.scope())) {
      throw new IllegalArgumentException("before and after must describe the same WorkItem");
    }
    return new WorkItemStatusChanged(
        current.scope().projectId().value(),
        current.key().value(),
        previous.status(),
        current.status());
  }
}
