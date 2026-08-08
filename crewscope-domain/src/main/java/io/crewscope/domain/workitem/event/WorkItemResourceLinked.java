package io.crewscope.domain.workitem.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import io.crewscope.domain.workitem.WorkItemResourceType;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Version 1 business payload emitted after an immutable WorkItem resource relation is committed. */
public record WorkItemResourceLinked(
    UUID workItemId,
    WorkItemResourceType resourceType,
    String resourceReference,
    Optional<String> label)
    implements DomainEvent {

  public WorkItemResourceLinked {
    workItemId = AggregateId.requireValue(workItemId, "WorkItemResourceLinked.workItemId");
    resourceType = Objects.requireNonNull(resourceType, "resourceType");
    if (resourceReference == null || resourceReference.isBlank()) {
      throw new IllegalArgumentException(
          "WorkItemResourceLinked.resourceReference must not be blank");
    }
    resourceReference = resourceReference.strip();
    label = Objects.requireNonNull(label, "label");
  }

  public static WorkItemResourceLinked from(WorkItemResourceLink link) {
    WorkItemResourceLink source = Objects.requireNonNull(link, "link");
    return new WorkItemResourceLinked(
        source.workItemId().value(),
        source.resourceType(),
        source.resourceReference(),
        source.label());
  }
}
