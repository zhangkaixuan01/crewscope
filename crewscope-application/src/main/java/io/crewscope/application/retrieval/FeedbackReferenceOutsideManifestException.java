package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.ManifestSourceKey;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;

/**
 * A feedback submission addressed a source key that no sealed manifest of the
 * execution lists as INJECTED (M10-I02c). Members can only judge evidence that
 * actually reached a prompt; candidates the budget cut are not feedback targets.
 */
public final class FeedbackReferenceOutsideManifestException extends RuntimeException {

    private final TaskExecutionId executionId;
    private final ManifestSourceKey source;

    public FeedbackReferenceOutsideManifestException(
            TaskExecutionId executionId, ManifestSourceKey source) {
        super("the reference " + Objects.requireNonNull(source, "source")
                + " is not in any injected set of execution "
                + Objects.requireNonNull(executionId, "executionId"));
        this.executionId = executionId;
        this.source = source;
    }

    public TaskExecutionId executionId() {
        return executionId;
    }

    public ManifestSourceKey source() {
        return source;
    }
}
