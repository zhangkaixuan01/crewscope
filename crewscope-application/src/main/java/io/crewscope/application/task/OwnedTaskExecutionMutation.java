package io.crewscope.application.task;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecution;

/** Application mutation executed inside the authoritative fenced ownership transaction. */
@FunctionalInterface
public interface OwnedTaskExecutionMutation {

    TaskExecution apply(
            TaskExecution current,
            long expectedVersion,
            Principal actor,
            UtcTimestamp authoritativeNow);
}
