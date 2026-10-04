package io.crewscope.application.retrieval;

import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;

/**
 * A concurrent writer sealed the manifest for the same (execution, attempt) first
 * (M10-I02b). The persistence adapter translates the unique violation; assembly
 * callers catch it and converge onto the stored manifest through findByAttempt, so
 * the idempotency race never surfaces as a duplicate row or an error.
 */
public final class InjectionManifestConflictException extends RuntimeException {

    private final TaskExecutionId executionId;
    private final int attempt;

    public InjectionManifestConflictException(TaskExecutionId executionId, int attempt) {
        super("a concurrent writer already sealed the injection manifest for execution "
                + Objects.requireNonNull(executionId, "executionId") + " attempt " + attempt);
        this.executionId = executionId;
        this.attempt = attempt;
    }

    public TaskExecutionId executionId() {
        return executionId;
    }

    public int attempt() {
        return attempt;
    }
}
