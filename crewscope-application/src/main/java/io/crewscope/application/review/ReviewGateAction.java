package io.crewscope.application.review;

import io.crewscope.application.availability.ActionStrength;
import io.crewscope.application.availability.TransitionBlockReason;
import io.crewscope.application.availability.TransitionRemedy;
import io.crewscope.domain.review.ReviewRequestStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime availability projection for one Review Gate command.
 *
 * <p>The mirror image of the WorkItem transition and Task control projections, for the four commands
 * {@code ReviewController} exposes on a ReviewRequest: execute, decide, request-changes and re-review.
 * There is no per-status edge metadata to read because a ReviewRequest is not a state machine with
 * declared transitions; the label, strength and reversibility of each command are attached here.
 *
 * <p>{@code targetStatus} is the request status the command leaves behind, not the edge it crosses:
 * Execute moves an OPEN request to IN_PROGRESS but leaves an already IN_PROGRESS one where it is, a
 * Gate Decision is an append-only conclusion that changes no status at all, and Re-review leaves the
 * invalidated predecessor untouched and publishes an OPEN successor.
 */
public record ReviewGateAction(
        String actionId,
        ReviewRequestStatus targetStatus,
        String label,
        ActionStrength strength,
        boolean reversible,
        boolean enabled,
        Optional<TransitionBlockReason> reason,
        Optional<TransitionRemedy> remedy) {

    public ReviewGateAction {
        if (actionId == null || actionId.isBlank() || label == null || label.isBlank()) {
            throw new IllegalArgumentException("Gate action metadata must not be blank");
        }
        actionId = actionId.strip();
        label = label.strip();
        targetStatus = Objects.requireNonNull(targetStatus, "targetStatus");
        strength = Objects.requireNonNull(strength, "strength");
        reason = Objects.requireNonNull(reason, "reason");
        remedy = Objects.requireNonNull(remedy, "remedy");
        if (enabled && reason.isPresent()) {
            throw new IllegalArgumentException("enabled Gate action cannot have a block reason");
        }
        if (enabled && remedy.isPresent()) {
            throw new IllegalArgumentException("enabled Gate action cannot have a remedy");
        }
        if (!enabled && reason.isEmpty()) {
            throw new IllegalArgumentException("disabled Gate action must have a block reason");
        }
        if (remedy.isPresent() && remedy.orElseThrow().route().isEmpty()) {
            throw new IllegalArgumentException("Gate action remedy must carry a route");
        }
    }

    static ReviewGateAction enabled(
            String actionId,
            ReviewRequestStatus targetStatus,
            String label,
            ActionStrength strength,
            boolean reversible) {
        return new ReviewGateAction(
                actionId, targetStatus, label, strength, reversible, true,
                Optional.empty(), Optional.empty());
    }

    static ReviewGateAction disabled(
            ReviewGateAction published,
            TransitionBlockReason reason,
            Optional<TransitionRemedy> remedy) {
        return new ReviewGateAction(
                published.actionId(),
                published.targetStatus(),
                published.label(),
                published.strength(),
                published.reversible(),
                false,
                Optional.of(reason),
                remedy);
    }
}
