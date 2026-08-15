package io.crewscope.application.runtime;

import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.RuntimeWorker;
import java.util.List;
import java.util.Objects;

/** Fixed-query persistence result used to derive member and operations Runtime views. */
public record RuntimeObservationSnapshot(
        List<ExecutionRuntime> runtimes,
        List<RuntimeWorker> workers,
        List<RuntimeWaitingExecution> waitingExecutions) {

    public RuntimeObservationSnapshot {
        runtimes = List.copyOf(Objects.requireNonNull(runtimes, "runtimes"));
        workers = List.copyOf(Objects.requireNonNull(workers, "workers"));
        waitingExecutions = List.copyOf(
                Objects.requireNonNull(waitingExecutions, "waitingExecutions"));
    }
}
