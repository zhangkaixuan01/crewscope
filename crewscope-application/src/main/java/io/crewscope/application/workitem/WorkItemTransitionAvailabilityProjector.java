package io.crewscope.application.workitem;

import io.crewscope.application.availability.TransitionBlockReason;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemTransitionCatalog;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The single adjudication of whether a WorkItem's existing state-machine edges are executable now.
 *
 * <p>Two surfaces consume it and must never disagree: the per-object availability endpoint, which
 * returns every edge so a detail view can explain why an action is unavailable, and the WorkDesk
 * projection, which keeps only the executable edges. {@link #enabled} is
 * {@link #all} filtered — not a second rule set — because M9 forbids a list view and a detail view
 * from each deciding for themselves.
 *
 * <p>The verdicts are intentionally narrow: archival, external ownership and missing participation.
 * The remaining reason constants have no rule on either the projection side or the command side, so
 * projecting one would offer an action the command then rejects. See
 * {@code docs/api/M9-状态流转可用性API契约.md}.
 */
public final class WorkItemTransitionAvailabilityProjector {

  /** Every edge the existing state machine allows, each marked enabled or carrying its reason. */
  public List<WorkItemAvailableTransition> all(
      WorkItemStatus status, boolean nativeSource, boolean participates) {
    return WorkItemTransitionCatalog.from(status).stream()
        .map(edge -> evaluate(status, nativeSource, participates, edge))
        .toList();
  }

  /**
   * Only the executable edges.
   *
   * <p>A list row cannot render disabled buttons, and a disabled entry without its reason on screen
   * would be the silent dead control M9 set out to remove, so the WorkDesk is handed a shorter
   * honest list instead of a longer ambiguous one. Disabled edges stay available where they can be
   * explained: the per-object availability endpoint.
   */
  public List<WorkItemAvailableTransition> enabled(
      WorkItemStatus status, boolean nativeSource, boolean participates) {
    return all(status, nativeSource, participates).stream()
        .filter(WorkItemAvailableTransition::enabled)
        .toList();
  }

  private static WorkItemAvailableTransition evaluate(
      WorkItemStatus status,
      boolean nativeSource,
      boolean participates,
      WorkItemTransitionCatalog.Edge edge) {
    if (Objects.requireNonNull(status, "status") == WorkItemStatus.ARCHIVED) {
      return WorkItemAvailableTransition.disabled(
          edge, TransitionBlockReason.ARCHIVED, Optional.empty());
    }
    if (!nativeSource) {
      return WorkItemAvailableTransition.disabled(
          edge, TransitionBlockReason.EXTERNAL_PROVIDER_MANAGED, Optional.empty());
    }
    if (!participates) {
      return WorkItemAvailableTransition.disabled(
          edge, TransitionBlockReason.PERMISSION_DENIED, Optional.empty());
    }
    return WorkItemAvailableTransition.enabled(edge);
  }
}
