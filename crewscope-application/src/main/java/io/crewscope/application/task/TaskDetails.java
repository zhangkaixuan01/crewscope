package io.crewscope.application.task;

import io.crewscope.domain.task.Task;
import java.util.List;
import java.util.Objects;

/** Consistent member-facing Task aggregate and ordered attempt rows. */
public record TaskDetails(Task task, List<TaskAttempt> attempts) {

    public TaskDetails {
        Task requiredTask = Objects.requireNonNull(task, "task");
        List<TaskAttempt> requiredAttempts =
                List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        if (requiredAttempts.stream().anyMatch(value ->
                !value.execution().taskId().equals(requiredTask.id())
                        || !value.execution().scope().equals(requiredTask.scope()))) {
            throw new IllegalArgumentException("attempts must belong to the Task");
        }
        task = requiredTask;
        attempts = requiredAttempts;
    }
}
