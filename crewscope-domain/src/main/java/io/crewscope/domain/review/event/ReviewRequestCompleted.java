package io.crewscope.domain.review.event;

import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Safe event emitted after validated Reviewer findings and request completion commit together. */
public record ReviewRequestCompleted(
        ReviewRequestId reviewRequestId,
        TaskExecutionId taskExecutionId,
        long requestVersion,
        TaskFactHash requestHash) implements DomainEvent {

    public ReviewRequestCompleted {
        reviewRequestId = Objects.requireNonNull(reviewRequestId, "reviewRequestId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        requestHash = Objects.requireNonNull(requestHash, "requestHash");
    }

    public static ReviewRequestCompleted from(ReviewRequest request) {
        ReviewRequest value = Objects.requireNonNull(request, "request");
        return new ReviewRequestCompleted(
                value.id(), value.taskExecutionId(), value.version(), value.requestHash());
    }
}
