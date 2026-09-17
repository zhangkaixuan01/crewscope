package io.crewscope.application.task;

import io.crewscope.application.availability.ActionStrength;
import io.crewscope.application.availability.TransitionBlockReason;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import io.crewscope.domain.task.TaskExecutionWaiting;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The single adjudication of whether a Task execution attempt may be paused, resumed, cancelled or
 * retried right now.
 *
 * <p>Every verdict is the command's own precondition: the predicates come from {@link TaskExecution},
 * which applies the same ones inside {@code requestPause}, {@code requestCancel} and the resume path.
 * The projector adds no rule of its own — it only pairs each command with the reason a refusal would
 * carry, so a disabled control and a rejected command can never disagree.
 *
 * <p>One precondition is deliberately absent because the projection cannot observe it: Resume and
 * Retry also require an interrupted AgentRun with a matching pending interrupt, which lives in
 * separate aggregates. A projection therefore reports those two as enabled whenever the execution
 * facts allow it, and the command may still refuse on the missing runtime fact. That gap is recorded
 * in {@code docs/api/M9-状态流转可用性API契约.md} rather than papered over with a fabricated
 * precondition.
 */
public final class TaskControlAvailabilityProjector {

    private static final List<ControlActionMetadata> CATALOG = List.of(
            new ControlActionMetadata(
                    "pause",
                    TaskExecutionStatus.PAUSE_REQUESTED,
                    "暂停执行",
                    ActionStrength.SECONDARY,
                    true,
                    TaskExecution::canRequestPause),
            new ControlActionMetadata(
                    "resume",
                    TaskExecutionStatus.READY,
                    "恢复执行",
                    ActionStrength.SECONDARY,
                    true,
                    TaskExecution::canResume),
            new ControlActionMetadata(
                    "cancel",
                    TaskExecutionStatus.CANCEL_REQUESTED,
                    "取消执行",
                    ActionStrength.DANGER,
                    false,
                    TaskExecution::canRequestCancel),
            new ControlActionMetadata(
                    "retry",
                    TaskExecutionStatus.READY,
                    "重试执行",
                    ActionStrength.PRIMARY,
                    true,
                    TaskExecution::canRetry));

    /** Every control command, each marked enabled or carrying the reason it is refused. */
    public List<TaskControlAction> all(TaskExecution execution, boolean authorized) {
        TaskExecution required = Objects.requireNonNull(execution, "execution");
        return CATALOG.stream()
                .map(action -> evaluate(required, authorized, action))
                .toList();
    }

    /**
     * Only the executable commands.
     *
     * <p>Used by surfaces that render a control bar and have no room to explain a disabled button;
     * a surface that can explain reads {@link #all} instead and keeps the reasons on screen.
     */
    public List<TaskControlAction> enabled(TaskExecution execution, boolean authorized) {
        return all(execution, authorized).stream()
                .filter(TaskControlAction::enabled)
                .toList();
    }

    private static TaskControlAction evaluate(
            TaskExecution execution, boolean authorized, ControlActionMetadata action) {
        TaskControlAction published = TaskControlAction.enabled(
                action.actionId(),
                action.targetStatus(),
                action.label(),
                action.strength(),
                action.reversible());
        if (!authorized) {
            return TaskControlAction.disabled(published, TransitionBlockReason.PERMISSION_DENIED);
        }
        if (action.permitted().test(execution)) {
            return published;
        }
        return TaskControlAction.disabled(published, blockedReason(execution));
    }

    /**
     * Explains a refusal caused by the execution's own status.
     *
     * <p>When the attempt is parked on a decision only a human can make, the status is refused for a
     * reason the member can act on, so the gate names itself instead of the generic status message.
     * Enabled and disabled are unchanged by this: the same commands are executable either way, and
     * only the wording of the refusal differs.
     */
    private static TransitionBlockReason blockedReason(TaskExecution execution) {
        return awaitingHumanDecision(execution)
                ? TransitionBlockReason.GATE_NOT_PASSED
                : TransitionBlockReason.STATUS_NOT_ALLOWED;
    }

    private static boolean awaitingHumanDecision(TaskExecution execution) {
        return execution.status() == TaskExecutionStatus.WAITING
                && execution.waiting()
                        .map(TaskExecutionWaiting::reason)
                        .filter(TaskControlAvailabilityProjector::isHumanDecision)
                        .isPresent();
    }

    private static boolean isHumanDecision(TaskExecutionWaitReason reason) {
        return reason == TaskExecutionWaitReason.CONFIRMATION
                || reason == TaskExecutionWaitReason.REVIEW
                || reason == TaskExecutionWaitReason.USER_INPUT;
    }

    private record ControlActionMetadata(
            String actionId,
            TaskExecutionStatus targetStatus,
            String label,
            ActionStrength strength,
            boolean reversible,
            Predicate<TaskExecution> permitted) {}
}
