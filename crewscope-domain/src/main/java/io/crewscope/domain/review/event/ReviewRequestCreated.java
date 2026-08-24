package io.crewscope.domain.review.event;

import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import java.util.Objects;

/** Safe event emitted after one exact ReviewRequest authority graph is committed. */
public record ReviewRequestCreated(
        ReviewRequestId reviewRequestId,
        TaskId taskId,
        TaskExecutionId taskExecutionId,
        int attempt,
        long requestRevision,
        TaskFactHash requestHash,
        TaskFactHash contextHash) implements DomainEvent {

    public ReviewRequestCreated {
        reviewRequestId = Objects.requireNonNull(reviewRequestId, "reviewRequestId");
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        requestHash = Objects.requireNonNull(requestHash, "requestHash");
        contextHash = Objects.requireNonNull(contextHash, "contextHash");
    }

    public static ReviewRequestCreated from(ReviewRequest request) {
        ReviewRequest value = Objects.requireNonNull(request, "request");
        return new ReviewRequestCreated(
                value.id(), value.taskId(), value.taskExecutionId(), value.attempt(),
                value.revision(), value.requestHash(), value.contextPackage().contextHash());
    }
}
