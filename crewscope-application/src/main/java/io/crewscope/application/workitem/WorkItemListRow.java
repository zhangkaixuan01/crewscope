package io.crewscope.application.workitem;

import io.crewscope.domain.workitem.WorkItem;
import java.util.List;
import java.util.Objects;

/**
 * One WorkItem list row together with the edges the server already adjudicated for it.
 *
 * <p>Same discipline as {@code WorkDeskItem.availableActions}: the row carries the verdict, never the
 * rule. The Repository Port hands the application layer plain aggregates; the availability a member
 * may execute is decided once per request by
 * {@link WorkItemTransitionAvailabilityProjector} and attached here, so a list row, a board card and
 * the detail panel cannot each reach their own conclusion.
 *
 * <p>The full edge list is carried, not only the executable subset: a list row renders a menu, and a
 * menu can show why an action is unavailable — unlike the WorkDesk row, which has no room to explain
 * one and is therefore handed the shorter honest list.
 */
public record WorkItemListRow(
    WorkItem workItem, List<WorkItemAvailableTransition> availableActions) {

  public WorkItemListRow {
    workItem = Objects.requireNonNull(workItem, "workItem");
    availableActions = List.copyOf(Objects.requireNonNull(availableActions, "availableActions"));
  }
}
