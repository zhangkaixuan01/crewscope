package io.crewscope.application.retrieval;

import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;

/**
 * A different claimed set already exists for the same (execution, attempt)
 * (M10-I02c). The receipt is an immutable fact of what the model said: an identical
 * set replays, a different set is a conflict that carries the stored receipt.
 */
public final class InjectionClaimedReferenceConflictException extends RuntimeException {

    private final TaskExecutionId executionId;
    private final int attempt;
    private final InjectionClaimedReferences stored;

    public InjectionClaimedReferenceConflictException(InjectionClaimedReferences stored) {
        super("a different claimed set is already stored for execution "
                + Objects.requireNonNull(stored, "stored").executionId()
                + " attempt " + stored.attempt());
        this.executionId = stored.executionId();
        this.attempt = stored.attempt();
        this.stored = stored;
    }

    public TaskExecutionId executionId() {
        return executionId;
    }

    public int attempt() {
        return attempt;
    }

    public InjectionClaimedReferences stored() {
        return stored;
    }
}
