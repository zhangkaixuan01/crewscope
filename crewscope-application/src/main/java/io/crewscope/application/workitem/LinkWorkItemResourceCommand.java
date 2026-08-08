package io.crewscope.application.workitem;

import io.crewscope.domain.workitem.WorkItemResourceType;
import java.util.Objects;
import java.util.Optional;

/** Immutable resource relation requested for one WorkItem. */
public record LinkWorkItemResourceCommand(
    WorkItemResourceType resourceType, String resourceReference, Optional<String> label) {

  public LinkWorkItemResourceCommand {
    resourceType = Objects.requireNonNull(resourceType, "resourceType");
    resourceReference = Objects.requireNonNull(resourceReference, "resourceReference");
    label = Objects.requireNonNull(label, "label");
  }
}
