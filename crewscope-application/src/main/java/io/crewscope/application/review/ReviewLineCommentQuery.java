package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewLineCommentId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.util.Objects;
import java.util.Optional;

/** Cursor-friendly query for comments in one exact ReviewRequest scope. */
public record ReviewLineCommentQuery(
        OrganizationId organizationId,
        TeamId teamId,
        TaskId taskId,
        TaskExecutionId executionId,
        ReviewRequestId reviewRequestId,
        Optional<String> after,
        int limit) {
    public ReviewLineCommentQuery {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        taskId = Objects.requireNonNull(taskId, "taskId");
        executionId = Objects.requireNonNull(executionId, "executionId");
        reviewRequestId = Objects.requireNonNull(reviewRequestId, "reviewRequestId");
        after = Objects.requireNonNull(after, "after").map(String::strip);
        after.ifPresent(value -> {
            try {
                ReviewLineCommentId.from(value);
            } catch (RuntimeException exception) {
                throw new DomainValidationException("reviewLineComment.after", "must be a canonical comment UUID");
            }
        });
        if (limit < 1 || limit > 200) {
            throw new DomainValidationException("reviewLineComment.limit", "must be between 1 and 200");
        }
    }
}
