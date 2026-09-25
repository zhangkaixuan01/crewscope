package io.crewscope.application.workdesk;

import io.crewscope.application.workitem.WorkItemAvailableTransition;
import io.crewscope.application.workitem.WorkItemExecutionSummary;
import io.crewscope.application.workitem.WorkItemTransitionSubject;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Safe member-facing item in a WorkDesk section.
 *
 * <p>{@code availableActions} holds only the transitions this member may execute now: a list row
 * cannot explain a disabled action, so it is not offered one. {@code transitionSubject} is present
 * exactly for rows backed by a WorkItem, and carries facts only — the adapter reports them and the
 * application layer decides availability, so no policy reaches into the JDBC read.
 *
 * <p>M9b-A06 row facts: {@code workItemId}/{@code workItemTitle} name the WorkItem a row speaks
 * about even when the row itself is an execution or review; {@code rowSummary} is the shared
 * execution/todo read model the lists and the detail panel also serve; {@code waitingOn} derives
 * from the summary's first reason that names a principal. All four are empty on rows without a
 * WorkItem behind them.
 */
public record WorkDeskItem(
    String objectType,
    String objectId,
    Optional<String> projectId,
    Optional<String> title,
    String status,
    Instant updatedAt,
    Optional<String> responsibilityRole,
    boolean needsAction,
    String urgency,
    Optional<Integer> progress,
    List<WorkItemAvailableTransition> availableActions,
    Optional<WorkItemTransitionSubject> transitionSubject,
    String route,
    Optional<String> workItemId,
    Optional<String> workItemTitle,
    Optional<WorkItemExecutionSummary> rowSummary,
    Optional<WorkDeskWaitingOn> waitingOn) {

  public WorkDeskItem {
    objectType = requireText(objectType, "objectType");
    objectId = requireText(objectId, "objectId");
    projectId = Objects.requireNonNull(projectId, "projectId");
    title = Objects.requireNonNull(title, "title");
    status = requireText(status, "status");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    responsibilityRole = Objects.requireNonNull(responsibilityRole, "responsibilityRole");
    urgency = requireText(urgency, "urgency");
    progress = Objects.requireNonNull(progress, "progress");
    progress.ifPresent(value -> {
      if (value < 0 || value > 100) throw new IllegalArgumentException("progress must be 0..100");
    });
    availableActions = List.copyOf(Objects.requireNonNull(availableActions, "availableActions"));
    transitionSubject = Objects.requireNonNull(transitionSubject, "transitionSubject");
    if (transitionSubject.isEmpty() && !availableActions.isEmpty()) {
      throw new IllegalArgumentException(
          "a row without a transition subject cannot carry available actions");
    }
    route = requireText(route, "route");
    workItemId = Objects.requireNonNull(workItemId, "workItemId");
    workItemTitle = Objects.requireNonNull(workItemTitle, "workItemTitle");
    rowSummary = Objects.requireNonNull(rowSummary, "rowSummary");
    waitingOn = Objects.requireNonNull(waitingOn, "waitingOn");
    if (workItemId.isEmpty() && (rowSummary.isPresent() || waitingOn.isPresent())) {
      throw new IllegalArgumentException("row facts require the WorkItem the row speaks about");
    }
    if (workItemId.isPresent() && rowSummary.isPresent()
        && !workItemId.orElseThrow().equals(rowSummary.orElseThrow().workItemId().toString())) {
      throw new IllegalArgumentException("rowSummary must belong to the row's own WorkItem");
    }
  }

  /** The pre-A06 shape: a row the adapter read before the service layered facts onto it. */
  public WorkDeskItem(
      String objectType,
      String objectId,
      Optional<String> projectId,
      Optional<String> title,
      String status,
      Instant updatedAt,
      Optional<String> responsibilityRole,
      boolean needsAction,
      String urgency,
      Optional<Integer> progress,
      List<WorkItemAvailableTransition> availableActions,
      Optional<WorkItemTransitionSubject> transitionSubject,
      String route) {
    this(objectType, objectId, projectId, title, status, updatedAt, responsibilityRole,
        needsAction, urgency, progress, availableActions, transitionSubject, route,
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  /** Rebinds the policy-decided edges while keeping every fact already layered onto the row. */
  public WorkDeskItem withActions(
      List<WorkItemAvailableTransition> actions, Optional<WorkItemTransitionSubject> subject) {
    return new WorkDeskItem(objectType, objectId, projectId, title, status, updatedAt,
        responsibilityRole, needsAction, urgency, progress, actions, subject, route,
        workItemId, workItemTitle, rowSummary, waitingOn);
  }

  /** Layers the batched summary onto this row; empty inputs leave the row as it was read. */
  public WorkDeskItem withRowFacts(
      Optional<WorkItemExecutionSummary> summary, Optional<WorkDeskWaitingOn> whom) {
    return new WorkDeskItem(objectType, objectId, projectId, title, status, updatedAt,
        responsibilityRole, needsAction, urgency, progress, availableActions, transitionSubject,
        route, workItemId, workItemTitle, summary, whom);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    return value.strip();
  }
}
