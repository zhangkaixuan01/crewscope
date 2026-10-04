package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.ManifestSourceKey;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Objects;

/**
 * A claimed receipt referenced a source key outside the sealed manifest's INJECTED
 * set for that attempt (M10-I02c). The model may only claim what was injected —
 * free-floating references cannot impersonate system evidence (plan §4.2).
 */
public final class ClaimedReferenceOutsideManifestException extends RuntimeException {

    private final TaskExecutionId executionId;
    private final int attempt;
    private final List<ManifestSourceKey> outside;

    public ClaimedReferenceOutsideManifestException(
            TaskExecutionId executionId, int attempt, List<ManifestSourceKey> outside) {
        super("claimed references outside the sealed manifest of execution "
                + Objects.requireNonNull(executionId, "executionId") + " attempt " + attempt
                + ": " + Objects.requireNonNull(outside, "outside"));
        this.executionId = executionId;
        this.attempt = attempt;
        this.outside = List.copyOf(outside);
    }

    public TaskExecutionId executionId() {
        return executionId;
    }

    public int attempt() {
        return attempt;
    }

    public List<ManifestSourceKey> outside() {
        return outside;
    }
}
