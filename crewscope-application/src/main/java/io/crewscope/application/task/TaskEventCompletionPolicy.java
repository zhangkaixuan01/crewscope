package io.crewscope.application.task;

import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskStatus;

/** Decides when no later business event is expected for an open Task SSE connection. */
@FunctionalInterface
public interface TaskEventCompletionPolicy {

    TaskEventCompletionPolicy TASK_TERMINAL = task -> {
        TaskStatus status = task.status();
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED;
    };

    boolean streamComplete(Task task);
}
