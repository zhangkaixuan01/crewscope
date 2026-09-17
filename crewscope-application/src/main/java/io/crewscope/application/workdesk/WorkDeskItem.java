package io.crewscope.application.workdesk;

import io.crewscope.application.workitem.WorkItemAvailableTransition;
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
    String route) {

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
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    return value.strip();
  }
}
