package io.crewscope.application.task;

/** Application boundary used by a durable Worker loop to claim bounded TaskExecution batches. */
@FunctionalInterface
public interface TaskClaimScheduler {

    /** Claims at most {@code requestedLimit} executions and returns secrets exactly once. */
    TaskClaimBatchResult claim(int requestedLimit);
}
