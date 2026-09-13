package io.crewscope.domain.review;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;

/** Mutable Review line discussion with a stable Diff anchor and soft-delete tombstone. */
public final class ReviewLineComment {
    public static final int MAX_CONTENT_LENGTH = 20_000;

    private final ReviewLineCommentId id;
    private final WorkItemScope scope;
    private final ReviewRequestId reviewRequestId;
    private final TaskId taskId;
    private final int attempt;
    private final TaskExecutionId taskExecutionId;
    private final PrincipalId authorPrincipalId;
    private final ReviewCommentAnchor anchor;
    private final String content;
    private final ReviewCommentAnchorState anchorState;
    private final boolean deleted;
    private final long version;
    private final AuditMetadata audit;

    private ReviewLineComment(
            ReviewLineCommentId id,
            WorkItemScope scope,
            ReviewRequestId reviewRequestId,
            TaskId taskId,
            int attempt,
            TaskExecutionId taskExecutionId,
            PrincipalId authorPrincipalId,
            ReviewCommentAnchor anchor,
            String content,
            ReviewCommentAnchorState anchorState,
            boolean deleted,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.reviewRequestId = Objects.requireNonNull(reviewRequestId, "reviewRequestId");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        if (attempt < 1) throw new DomainValidationException("reviewLineComment.attempt", "must be positive");
        this.attempt = attempt;
        this.taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        this.authorPrincipalId = Objects.requireNonNull(authorPrincipalId, "authorPrincipalId");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.content = requireContent(content);
        this.anchorState = Objects.requireNonNull(anchorState, "anchorState");
        this.deleted = deleted;
        if (version < 0) {
            throw new DomainValidationException("reviewLineComment.version", "must be non-negative");
        }
        this.version = version;
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Creates a comment after the caller has resolved the exact Review authority and scope. */
    public static ReviewLineComment add(
            ReviewLineCommentId id,
            WorkItemScope scope,
            ReviewRequestId reviewRequestId,
            TaskId taskId,
            int attempt,
            TaskExecutionId taskExecutionId,
            ReviewCommentAnchor anchor,
            Principal author,
            String content,
            UtcTimestamp occurredAt) {
        Principal required = Objects.requireNonNull(author, "author");
        if (!required.canAct() || !required.scope().organizationId().equals(scope.organizationId())) {
            throw new DomainValidationException(
                    "reviewLineComment.authorPrincipalId", "must be active in the Review Organization");
        }
        return new ReviewLineComment(
                id, scope, reviewRequestId, taskId, attempt, taskExecutionId, required.id(), anchor, content,
                ReviewCommentAnchorState.ACTIVE, false, 0, AuditMetadata.createdBy(required.id(), occurredAt));
    }

    public static ReviewLineComment reconstitute(
            ReviewLineCommentId id,
            WorkItemScope scope,
            ReviewRequestId reviewRequestId,
            TaskId taskId,
            int attempt,
            TaskExecutionId taskExecutionId,
            PrincipalId authorPrincipalId,
            ReviewCommentAnchor anchor,
            String content,
            ReviewCommentAnchorState anchorState,
            boolean deleted,
            long version,
            AuditMetadata audit) {
        return new ReviewLineComment(id, scope, reviewRequestId, taskId, attempt, taskExecutionId, authorPrincipalId,
                anchor, content, anchorState, deleted, version, audit);
    }

    public ReviewLineComment edit(Principal actor, long expectedVersion, String nextContent, UtcTimestamp occurredAt) {
        requireOwner(actor);
        requireVersion(expectedVersion);
        if (deleted) {
            throw new DomainValidationException("reviewLineComment.deleted", "deleted comments cannot be edited");
        }
        return new ReviewLineComment(id, scope, reviewRequestId, taskId, attempt, taskExecutionId, authorPrincipalId,
                anchor, nextContent, anchorState, false, version + 1, audit.modifiedBy(actor.id(), occurredAt));
    }

    public ReviewLineComment delete(Principal actor, long expectedVersion, UtcTimestamp occurredAt) {
        requireOwner(actor);
        requireVersion(expectedVersion);
        if (deleted) {
            throw new DomainValidationException("reviewLineComment.deleted", "comment is already deleted");
        }
        return new ReviewLineComment(id, scope, reviewRequestId, taskId, attempt, taskExecutionId, authorPrincipalId,
                anchor, content, anchorState, true, version + 1, audit.modifiedBy(actor.id(), occurredAt));
    }

    /** Returns a copy with an updated mapping state while retaining the original text and anchor. */
    public ReviewLineComment withAnchorState(ReviewCommentAnchorState state) {
        return new ReviewLineComment(id, scope, reviewRequestId, taskId, attempt, taskExecutionId, authorPrincipalId,
                anchor, content, state, deleted, version, audit);
    }

    private void requireOwner(Principal actor) {
        if (!authorPrincipalId.equals(Objects.requireNonNull(actor, "actor").id())) {
            throw new DomainValidationException("reviewLineComment.authorPrincipalId", "only the author may modify this comment");
        }
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new OptimisticLockConflictException("ReviewLineComment", id, expectedVersion, version);
        }
    }

    private static String requireContent(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("reviewLineComment.content", "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_CONTENT_LENGTH) {
            throw new DomainValidationException("reviewLineComment.content", "must contain at most " + MAX_CONTENT_LENGTH + " characters");
        }
        if (normalized.indexOf('\0') >= 0) {
            throw new DomainValidationException("reviewLineComment.content", "must be valid text");
        }
        return normalized;
    }

    public ReviewLineCommentId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public ReviewRequestId reviewRequestId() { return reviewRequestId; }
    public TaskId taskId() { return taskId; }
    public int attempt() { return attempt; }
    public TaskExecutionId taskExecutionId() { return taskExecutionId; }
    public PrincipalId authorPrincipalId() { return authorPrincipalId; }
    public ReviewCommentAnchor anchor() { return anchor; }
    public String content() { return content; }
    public ReviewCommentAnchorState anchorState() { return anchorState; }
    public boolean deleted() { return deleted; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
