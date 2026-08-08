package io.crewscope.application.workitem;

import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemComment;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import java.util.List;
import java.util.Objects;

/** Consistent WorkItem detail snapshot with its immutable collaboration children. */
public record WorkItemDetails(
    WorkItem workItem,
    List<WorkItemComment> comments,
    List<WorkItemResourceLink> resourceLinks) {

  public WorkItemDetails {
    workItem = Objects.requireNonNull(workItem, "workItem");
    comments = List.copyOf(Objects.requireNonNull(comments, "comments"));
    resourceLinks = List.copyOf(Objects.requireNonNull(resourceLinks, "resourceLinks"));
  }
}
