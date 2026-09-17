package io.crewscope.application.task;

import io.crewscope.domain.task.TaskExecution;
import java.util.List;
import java.util.Objects;

/**
 * One execution attempt together with the control actions the requesting member may execute on it.
 *
 * <p>The row carries the verdict, never the rule: the same reasoning as a WorkItem list row, where a
 * surface that renders a control bar must not re-derive from the execution status what the server
 * already decided. The full command set is carried, disabled entries included, so a control bar has
 * the reason to explain rather than a silently absent button.
 */
public record TaskAttempt(TaskExecution execution, List<TaskControlAction> availableActions) {

    public TaskAttempt {
        execution = Objects.requireNonNull(execution, "execution");
        availableActions = List.copyOf(Objects.requireNonNull(availableActions, "availableActions"));
    }
}
