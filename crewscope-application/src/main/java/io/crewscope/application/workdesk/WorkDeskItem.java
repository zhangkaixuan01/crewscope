package io.crewscope.application.workdesk;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Safe member-facing item in a WorkDesk section. */
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
    List<String> availableActions,
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
    route = requireText(route, "route");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    return value.strip();
  }
}
