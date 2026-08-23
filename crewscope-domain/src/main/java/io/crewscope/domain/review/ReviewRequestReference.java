package io.crewscope.domain.review;

import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;

/** Exact ReviewRequest revision used by Findings, Decisions and modification rounds. */
public record ReviewRequestReference(
        WorkItemScope scope,
        TaskId taskId,
        TaskExecutionId taskExecutionId,
        int attempt,
        ReviewRequestId id,
        long revision,
        long version,
        ReviewSubjectReference subject,
        ContextPackageReference contextPackage,
        TaskFactHash requestHash) {

    public ReviewRequestReference {
        scope = Objects.requireNonNull(scope, "scope");
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        id = Objects.requireNonNull(id, "id");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        subject = Objects.requireNonNull(subject, "subject");
        contextPackage = Objects.requireNonNull(contextPackage, "contextPackage");
        requestHash = Objects.requireNonNull(requestHash, "requestHash");
    }

    public static ReviewRequestReference from(ReviewRequest request) {
        ReviewRequest required = Objects.requireNonNull(request, "request");
        return new ReviewRequestReference(
                required.scope(),
                required.taskId(),
                required.taskExecutionId(),
                required.attempt(),
                required.id(),
                required.revision(),
                required.version(),
                required.subject(),
                required.contextPackage(),
                required.requestHash());
    }
}
