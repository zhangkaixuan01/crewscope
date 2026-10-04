package io.crewscope.application.retrieval;

import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;

/**
 * No sealed manifest exists for the addressed (execution, attempt) (M10-I02c). A
 * claimed receipt must reconcile against an actual assembly; there is nothing to
 * claim against when the attempt never sealed one.
 */
public final class InjectionManifestNotSealedException extends RuntimeException {

    private final TaskExecutionId executionId;
    private final int attempt;

    public InjectionManifestNotSealedException(TaskExecutionId executionId, int attempt) {
        super("no sealed injection manifest exists for execution "
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
