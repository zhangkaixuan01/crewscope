package io.crewscope.application.runtime;

import io.crewscope.domain.runtime.RuntimeWorker;
import java.util.Objects;

/** Operations-only Worker facts with health derived at one authoritative instant. */
public record RuntimeWorkerObservation(
        RuntimeWorker worker,
        boolean runtimeActive,
        boolean heartbeatFresh,
        boolean claimable) {

    public RuntimeWorkerObservation {
        worker = Objects.requireNonNull(worker, "worker");
    }
}
