package io.crewscope.application.review;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ReviewLineComment;
import io.crewscope.domain.review.ReviewLineCommentId;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.util.Objects;
import java.util.Optional;

/** Authorized create/edit/delete boundary for Review line comments. */
public final class ReviewLineCommentCommandService {
    private final ReviewLineCommentRepository comments;
    private final ReviewRequestRepository requests;
    private final ContextPackageRepository contexts;
    private final WorkItemAccessPolicy accessPolicy;
    private final TimeProvider timeProvider;

    public ReviewLineCommentCommandService(
            ReviewLineCommentRepository comments,
            ReviewRequestRepository requests,
            ContextPackageRepository contexts,
            WorkItemAccessPolicy accessPolicy,
            TimeProvider timeProvider) {
        this.comments = Objects.requireNonNull(comments, "comments");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public ReviewLineComment add(TeamAccessContext access, OrganizationId organizationId,
            TeamId teamId, TaskId taskId, TaskExecutionId executionId,
            io.crewscope.domain.review.ReviewRequestId requestId,
            AddReviewLineCommentCommand command, String idempotencyKey) {
        Objects.requireNonNull(command, "command");
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        ReviewRequest request = authorize(access, organizationId, teamId, taskId, executionId, requestId);
        Optional<ReviewLineComment> replay = comments.findByIdempotencyKey(organizationId, normalizedKey);
        if (replay.isPresent()) {
            ReviewLineComment prior = replay.orElseThrow();
            if (prior.reviewRequestId().equals(request.id())
                    && prior.taskId().equals(taskId)
                    && prior.taskExecutionId().equals(executionId)
                    && prior.authorPrincipalId().equals(access.actor().id())
                    && prior.anchor().equals(command.anchor())
                    && prior.content().equals(command.content())) {
                return prior;
            }
            throw new io.crewscope.domain.shared.error.DomainValidationException(
                    "idempotencyKey", "is already used by another comment request");
        }
        ContextPackage context = context(organizationId, request);
        if (!ReviewLineCommentQueryService.isCurrent(command.anchor(), context)) {
            throw new io.crewscope.domain.shared.error.DomainValidationException(
                    "reviewLineComment.anchor", "does not resolve to the current Diff");
        }
        ReviewLineComment value = ReviewLineComment.add(ReviewLineCommentId.generate(), request.scope(),
                request.id(), taskId, request.attempt(), executionId, command.anchor(), access.actor(), command.content(), timeProvider.now());
        return comments.create(value, normalizedKey);
    }

    public ReviewLineComment edit(TeamAccessContext access, OrganizationId organizationId,
            TeamId teamId, TaskId taskId, TaskExecutionId executionId,
            io.crewscope.domain.review.ReviewRequestId requestId,
            ReviewLineCommentId commentId, ResolveReviewLineCommentCommand command, String idempotencyKey) {
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        ReviewLineComment current = loadAuthorized(access, organizationId, teamId, taskId, executionId, requestId, commentId);
        Optional<ReviewLineComment> replay = comments.findByIdempotencyKey(organizationId, normalizedKey);
        if (replay.isPresent()) {
            ReviewLineComment prior = replay.orElseThrow();
            boolean sameEdit = !command.deletion()
                    && !prior.deleted()
                    && prior.content().equals(command.content())
                    && prior.version() == command.expectedVersion() + 1;
            boolean sameDelete = command.deletion() && prior.deleted()
                    && prior.version() == command.expectedVersion() + 1;
            if (prior.id().equals(commentId) && (sameEdit || sameDelete)) return prior;
            throw new io.crewscope.domain.shared.error.DomainValidationException(
                    "idempotencyKey", "is already used by another comment request");
        }
        UtcTimestamp now = timeProvider.now();
        ReviewLineComment next = command.deletion()
                ? current.delete(access.actor(), command.expectedVersion(), now)
                : current.edit(access.actor(), command.expectedVersion(), command.content(), now);
        return comments.update(next, command.expectedVersion(), normalizedKey);
    }

    private ReviewLineComment loadAuthorized(TeamAccessContext access, OrganizationId organizationId,
            TeamId teamId, TaskId taskId, TaskExecutionId executionId,
            io.crewscope.domain.review.ReviewRequestId requestId, ReviewLineCommentId commentId) {
        authorize(access, organizationId, teamId, taskId, executionId, requestId);
        ReviewLineComment value = comments.findById(organizationId, commentId)
                .orElseThrow(() -> new AggregateNotFoundException("ReviewLineComment", commentId));
        if (!value.reviewRequestId().equals(requestId) || !value.taskExecutionId().equals(executionId)) {
            throw new AggregateNotFoundException("ReviewLineComment", commentId);
        }
        return value;
    }

    private ReviewRequest authorize(TeamAccessContext access, OrganizationId organizationId,
            TeamId teamId, TaskId taskId, TaskExecutionId executionId,
            io.crewscope.domain.review.ReviewRequestId requestId) {
        accessPolicy.requireVisibleTeam(access, organizationId, teamId);
        ReviewRequest request = requests.findById(organizationId, requestId)
                .orElseThrow(() -> new AggregateNotFoundException("ReviewRequest", requestId));
        if (!request.scope().teamId().equals(teamId) || !request.taskId().equals(taskId)
                || !request.taskExecutionId().equals(executionId)) {
            throw new AggregateNotFoundException("ReviewRequest", requestId);
        }
        return request;
    }

    private ContextPackage context(OrganizationId organizationId, ReviewRequest request) {
        return contexts.findById(organizationId, request.contextPackage().id())
                .orElseThrow(() -> new AggregateNotFoundException("ContextPackage", request.contextPackage().id()));
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new io.crewscope.domain.shared.error.DomainValidationException(
                    "idempotencyKey", "must contain between 1 and 200 characters");
        }
        try {
            return IdempotencyKey.from(value).value();
        } catch (IllegalArgumentException exception) {
            throw new io.crewscope.domain.shared.error.DomainValidationException(
                    "idempotencyKey", "has an invalid format");
        }
    }
}
