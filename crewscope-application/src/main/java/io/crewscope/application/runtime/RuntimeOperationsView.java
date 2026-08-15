package io.crewscope.application.runtime;

import io.crewscope.domain.runtime.ExecutionRuntime;
import java.util.List;
import java.util.Objects;

/** Permission-gated Runtime registry, Worker and WAITING_RUNTIME operations projection. */
public record RuntimeOperationsView(
        RuntimeFleetSummary summary,
        List<ExecutionRuntime> runtimes,
        List<RuntimeWorkerObservation> workers,
        List<RuntimeWaitingDiagnostic> waitingExecutions) {

    public RuntimeOperationsView {
        summary = Objects.requireNonNull(summary, "summary");
        runtimes = List.copyOf(Objects.requireNonNull(runtimes, "runtimes"));
        workers = List.copyOf(Objects.requireNonNull(workers, "workers"));
        waitingExecutions = List.copyOf(
                Objects.requireNonNull(waitingExecutions, "waitingExecutions"));
    }
}
