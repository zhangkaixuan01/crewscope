package io.crewscope.application.task;

import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import java.util.List;
import java.util.Objects;

/** Consistent member-facing Task aggregate and ordered attempt summaries. */
public record TaskDetails(Task task, List<TaskExecution> attempts) {

    public TaskDetails {
        Task requiredTask = Objects.requireNonNull(task, "task");
        List<TaskExecution> requiredAttempts =
                List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        if (requiredAttempts.stream().anyMatch(value -> !value.taskId().equals(requiredTask.id())
                || !value.scope().equals(requiredTask.scope()))) {
            throw new IllegalArgumentException("attempts must belong to the Task");
        }
        task = requiredTask;
        attempts = requiredAttempts;
    }
}
