package io.crewscope.application.workitem;

import io.crewscope.application.availability.TransitionBlockReason;
import io.crewscope.application.availability.TransitionRemedy;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemTransitionCatalog;
import java.util.Objects;
import java.util.Optional;

/** Runtime availability projection for one existing WorkItem state-machine edge. */
public record WorkItemAvailableTransition(
    String actionId,
    WorkItemStatus targetStatus,
    String label,
    WorkItemTransitionCatalog.Strength strength,
    boolean reversible,
    boolean enabled,
    Optional<TransitionBlockReason> reason,
    Optional<TransitionRemedy> remedy) {

  public WorkItemAvailableTransition {
    if (actionId == null || actionId.isBlank() || label == null || label.isBlank()) {
      throw new IllegalArgumentException("transition action metadata must not be blank");
    }
    actionId = actionId.strip();
    label = label.strip();
    targetStatus = Objects.requireNonNull(targetStatus, "targetStatus");
    strength = Objects.requireNonNull(strength, "strength");
    reason = Objects.requireNonNull(reason, "reason");
    remedy = Objects.requireNonNull(remedy, "remedy");
    if (enabled && reason.isPresent()) {
      throw new IllegalArgumentException("enabled transition cannot have a block reason");
    }
    if (enabled && remedy.isPresent()) {
      throw new IllegalArgumentException("enabled transition cannot have a remedy");
    }
    if (!enabled && reason.isEmpty()) {
      throw new IllegalArgumentException("disabled transition must have a block reason");
    }
  }

  public static WorkItemAvailableTransition enabled(
      WorkItemTransitionCatalog.Edge edge) {
    return from(edge, true, Optional.empty(), Optional.empty());
  }

  public static WorkItemAvailableTransition disabled(
      WorkItemTransitionCatalog.Edge edge,
      TransitionBlockReason reason,
      Optional<TransitionRemedy> remedy) {
    return from(edge, false, Optional.of(reason), remedy);
  }

  private static WorkItemAvailableTransition from(
      WorkItemTransitionCatalog.Edge edge,
      boolean enabled,
      Optional<TransitionBlockReason> reason,
      Optional<TransitionRemedy> remedy) {
    WorkItemTransitionCatalog.EdgeMetadata metadata = edge.metadata();
    return new WorkItemAvailableTransition(
        metadata.actionId(), edge.to(), metadata.label(), metadata.strength(),
        metadata.reversible(), enabled, reason, remedy);
  }
}
