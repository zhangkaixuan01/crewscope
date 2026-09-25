package io.crewscope.application.workitem;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemLabel;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemType;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** User-authored fields for one CrewScope-native WorkItem. */
public record CreateNativeWorkItemCommand(
    String key,
    WorkItemType type,
    String title,
    Optional<String> description,
    WorkItemPriority priority,
    Set<WorkItemLabel> labels,
    Optional<UtcTimestamp> dueAt) {

  public CreateNativeWorkItemCommand {
    type = Objects.requireNonNull(type, "type");
    title = Objects.requireNonNull(title, "title");
    if (title.strip().isEmpty() || title.strip().length() > 240)
      throw new io.crewscope.domain.shared.error.DomainValidationException("workItem.title", "must contain 1–240 characters");
    description = Objects.requireNonNull(description, "description");
    priority = Objects.requireNonNull(priority, "priority");
    labels = Set.copyOf(Objects.requireNonNull(labels, "labels"));
    dueAt = Objects.requireNonNull(dueAt, "dueAt");
  }
}
