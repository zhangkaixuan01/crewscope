package io.crewscope.application.runtime;

import java.util.Objects;

/** Operations-only current diagnosis for one WAITING_RUNTIME execution. */
public record RuntimeWaitingDiagnostic(
        RuntimeWaitingExecution waitingExecution, RuntimeWaitCause cause) {

    public RuntimeWaitingDiagnostic {
        waitingExecution = Objects.requireNonNull(waitingExecution, "waitingExecution");
        cause = Objects.requireNonNull(cause, "cause");
    }
}
