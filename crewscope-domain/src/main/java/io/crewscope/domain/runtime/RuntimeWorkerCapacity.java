package io.crewscope.domain.runtime;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Worker concurrency ceiling and its last reported active TaskExecution count. */
public record RuntimeWorkerCapacity(int maxConcurrentExecutions, int activeExecutions) {

    public static final int MAX_CONCURRENT_EXECUTIONS = 10_000;

    public RuntimeWorkerCapacity {
        if (maxConcurrentExecutions < 1
                || maxConcurrentExecutions > MAX_CONCURRENT_EXECUTIONS) {
            throw new DomainValidationException(
                    "runtimeWorker.capacity.maxConcurrentExecutions",
                    "must be between 1 and 10000");
        }
        if (activeExecutions < 0 || activeExecutions > maxConcurrentExecutions) {
            throw new DomainValidationException(
                    "runtimeWorker.capacity.activeExecutions",
                    "must be between zero and maxConcurrentExecutions");
        }
    }

    public int availableExecutions() {
        return maxConcurrentExecutions - activeExecutions;
    }

    public boolean hasAvailability() {
        return activeExecutions < maxConcurrentExecutions;
    }
}
