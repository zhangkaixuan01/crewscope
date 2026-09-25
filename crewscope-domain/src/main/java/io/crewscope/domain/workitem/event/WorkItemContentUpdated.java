package io.crewscope.domain.workitem.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.workitem.WorkItem;
import java.util.*;

/** Records exactly which planning fields changed, without copying content into activity feeds. */
public record WorkItemContentUpdated(UUID projectId, String itemKey, List<String> changedFields)
    implements DomainEvent {
  public WorkItemContentUpdated {
    Objects.requireNonNull(projectId, "projectId");
    itemKey = new io.crewscope.domain.workitem.WorkItemKey(itemKey).value();
    changedFields = List.copyOf(changedFields);
    if (!Set.of("title", "description", "priority", "labels", "dueAt").containsAll(changedFields)
        || new HashSet<>(changedFields).size() != changedFields.size())
      throw new IllegalArgumentException("Only distinct planning content fields may be recorded");
  }
  public static WorkItemContentUpdated from(WorkItem before, WorkItem after) {
    List<String> changed = new ArrayList<>();
    if (!before.title().equals(after.title())) changed.add("title");
    if (!before.description().equals(after.description())) changed.add("description");
    if (before.priority() != after.priority()) changed.add("priority");
    if (!before.labels().equals(after.labels())) changed.add("labels");
    if (!before.dueAt().equals(after.dueAt())) changed.add("dueAt");
    return new WorkItemContentUpdated(after.scope().projectId().value(), after.key().value(), changed);
  }
}
