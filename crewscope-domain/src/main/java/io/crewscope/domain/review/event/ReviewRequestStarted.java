package io.crewscope.domain.review.event;

import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Safe event emitted when the Reviewer invocation acquires the exact request ETag. */
public record ReviewRequestStarted(
        ReviewRequestId reviewRequestId,
        TaskExecutionId taskExecutionId,
        long requestVersion,
        TaskFactHash requestHash) implements DomainEvent {

    public ReviewRequestStarted {
        reviewRequestId = Objects.requireNonNull(reviewRequestId, "reviewRequestId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        requestHash = Objects.requireNonNull(requestHash, "requestHash");
    }

    public static ReviewRequestStarted from(ReviewRequest request) {
        ReviewRequest value = Objects.requireNonNull(request, "request");
        return new ReviewRequestStarted(
                value.id(), value.taskExecutionId(), value.version(), value.requestHash());
    }
}
