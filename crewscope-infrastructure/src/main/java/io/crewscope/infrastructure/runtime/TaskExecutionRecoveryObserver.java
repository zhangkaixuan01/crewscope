package io.crewscope.infrastructure.runtime;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecution;

/** Extends durable startup recovery before a TaskExecution is made claimable again. */
@FunctionalInterface
public interface TaskExecutionRecoveryObserver {

    TaskExecutionRecoveryObserver NOOP = (execution, authoritativeNow) -> {};

    /** Runs inside the same transaction that owns the RECOVERING TaskExecution row lock. */
    void beforeRequeue(TaskExecution execution, UtcTimestamp authoritativeNow);
}
