package io.crewscope.application.workitem;

import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemComment;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Consistent WorkItem detail snapshot with its immutable collaboration children.
 *
 * <p>{@code availableActions} is the same full edge list the per-object availability endpoint returns,
 * decided by the same {@link WorkItemTransitionAvailabilityProjector} in the same request, so the
 * detail panel no longer needs a second round trip that could observe a different verdict.
 *
 * <p>{@code summary} is the M9b-A06 execution/todo read model, assembled in the same transaction so
 * the detail panel and the list row it came from cannot disagree about runtime progress. Empty only
 * when the assembler could not see the row.
 */
public record WorkItemDetails(
    WorkItem workItem,
    List<WorkItemComment> comments,
    List<WorkItemResourceLink> resourceLinks,
    List<WorkItemAvailableTransition> availableActions,
    Optional<WorkItemExecutionSummary> summary) {

  public WorkItemDetails {
    workItem = Objects.requireNonNull(workItem, "workItem");
    comments = List.copyOf(Objects.requireNonNull(comments, "comments"));
    resourceLinks = List.copyOf(Objects.requireNonNull(resourceLinks, "resourceLinks"));
    availableActions = List.copyOf(Objects.requireNonNull(availableActions, "availableActions"));
    summary = Objects.requireNonNull(summary, "summary");
  }
}
