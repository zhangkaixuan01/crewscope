package io.crewscope.application.task;

import io.crewscope.application.availability.ActionStrength;
import io.crewscope.application.availability.TransitionBlockReason;
import io.crewscope.application.availability.TransitionRemedy;
import io.crewscope.domain.task.TaskExecutionStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime availability projection for one Task execution control command.
 *
 * <p>The mirror image of the WorkItem transition projection, for the four operational commands of
 * {@code TaskCommandController}: pause, resume, cancel and retry. There is no catalogue here because
 * the four commands are a closed set rather than a state machine with per-status edge metadata; the
 * label, strength and reversibility of each are presentation facts attached at projection time.
 *
 * <p>{@code targetStatus} is the execution status the command leaves behind, not the edge it crosses:
 * Pause stops at PAUSE_REQUESTED because a Worker still has to reach the safe point, Cancel stops at
 * CANCEL_REQUESTED for the same reason and may converge to CANCELLED in the same command, and Retry
 * leaves the failed attempt untouched and publishes a READY successor. It is what a member is
 * agreeing to, so it is the status after the command rather than the request inside it.
 */
public record TaskControlAction(
        String actionId,
        TaskExecutionStatus targetStatus,
        String label,
        ActionStrength strength,
        boolean reversible,
        boolean enabled,
        Optional<TransitionBlockReason> reason,
        Optional<TransitionRemedy> remedy) {

    public TaskControlAction {
        if (actionId == null || actionId.isBlank() || label == null || label.isBlank()) {
            throw new IllegalArgumentException("control action metadata must not be blank");
        }
        actionId = actionId.strip();
        label = label.strip();
        targetStatus = Objects.requireNonNull(targetStatus, "targetStatus");
        strength = Objects.requireNonNull(strength, "strength");
        reason = Objects.requireNonNull(reason, "reason");
        remedy = Objects.requireNonNull(remedy, "remedy");
        if (enabled && reason.isPresent()) {
            throw new IllegalArgumentException("enabled control action cannot have a block reason");
        }
        if (enabled && remedy.isPresent()) {
            throw new IllegalArgumentException("enabled control action cannot have a remedy");
        }
        if (!enabled && reason.isEmpty()) {
            throw new IllegalArgumentException("disabled control action must have a block reason");
        }
    }

    static TaskControlAction enabled(
            String actionId,
            TaskExecutionStatus targetStatus,
            String label,
            ActionStrength strength,
            boolean reversible) {
        return new TaskControlAction(
                actionId, targetStatus, label, strength, reversible, true,
                Optional.empty(), Optional.empty());
    }

    static TaskControlAction disabled(
            TaskControlAction published, TransitionBlockReason reason) {
        return new TaskControlAction(
                published.actionId(),
                published.targetStatus(),
                published.label(),
                published.strength(),
                published.reversible(),
                false,
                Optional.of(reason),
                Optional.empty());
    }
}
