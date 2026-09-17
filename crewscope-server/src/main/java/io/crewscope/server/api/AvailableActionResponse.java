package io.crewscope.server.api;

import io.crewscope.application.availability.TransitionBlockReason;
import io.crewscope.application.availability.TransitionRemedy;

/**
 * The one wire shape of an action the server has already adjudicated.
 *
 * <p>Every surface that offers actions serializes this record — the WorkItem availability endpoint, a
 * WorkItem list row, the personal WorkDesk, a ReviewRequest and a Task execution attempt. A second
 * record per surface would let two of them drift apart while both claiming to describe the same
 * adjudication, which is exactly what M9 forbids; the fields are therefore deliberately
 * surface-independent, and a surface that has no target status or undo semantics sends {@code null}
 * and {@code false} rather than inventing a value.
 *
 * <p>A disabled action always carries {@code reason} and {@code reasonMessage}: the client renders the
 * message beside the disabled control instead of leaving a dead button unexplained.
 */
public record AvailableActionResponse(
    String actionId,
    String targetStatus,
    String label,
    String strength,
    boolean reversible,
    boolean enabled,
    String reason,
    String reasonMessage,
    String remedyLabel,
    String remedyRoute) {

  /** Builds one entry from an adjudicated action, whatever object type produced it. */
  public static AvailableActionResponse of(
      String actionId,
      String targetStatus,
      String label,
      String strength,
      boolean reversible,
      boolean enabled,
      TransitionBlockReason reason,
      TransitionRemedy remedy) {
    return new AvailableActionResponse(
        actionId,
        targetStatus,
        label,
        strength,
        reversible,
        enabled,
        reason == null ? null : reason.name(),
        reason == null ? null : reason.message(),
        remedy == null ? null : remedy.label(),
        remedy == null ? null : remedy.route());
  }
}
