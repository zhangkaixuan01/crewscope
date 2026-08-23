package io.crewscope.domain.review;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Immutable code-change subject closed over the exact M4 Diff authority. */
public final class ReviewSubject {

    private final ReviewSubjectId id;
    private final ReviewSubjectType type;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId taskExecutionId;
    private final int attempt;
    private final ReviewDiffReference diff;
    private final TaskFactHash subjectHash;
    private final AuditMetadata audit;

    private ReviewSubject(
            ReviewSubjectId id,
            ReviewSubjectType type,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            ReviewDiffReference diff,
            Optional<TaskFactHash> expectedHash,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1) {
            throw new DomainValidationException("reviewSubject.attempt", "must be positive");
        }
        this.attempt = attempt;
        this.diff = Objects.requireNonNull(diff, "diff");
        if (!this.scope.equals(this.diff.scope())
                || !this.taskId.equals(this.diff.taskId())
                || !this.taskExecutionId.equals(this.diff.taskExecutionId())
                || this.attempt != this.diff.attempt()) {
            throw new DomainValidationException(
                    "reviewSubject.diff", "must share exact Scope, Task and attempt");
        }
        this.audit = Objects.requireNonNull(audit, "audit");
        this.subjectHash = calculateHash();
        Objects.requireNonNull(expectedHash, "expectedHash").ifPresent(expected -> {
            if (!expected.equals(this.subjectHash)) {
                throw new DomainValidationException(
                        "reviewSubject.subjectHash", "must match the exact subject facts");
            }
        });
    }

    public static ReviewSubject codeChange(
            ReviewSubjectId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            ReviewDiffReference diff,
            Principal actor,
            UtcTimestamp createdAt) {
        WorkItemScope requiredScope = Objects.requireNonNull(scope, "scope");
        PrincipalId actorId = ReviewActorPolicy.requireActiveInScope(
                actor, requiredScope, "reviewSubject.createdByPrincipalId");
        return new ReviewSubject(
                id,
                ReviewSubjectType.CODE_CHANGE,
                requiredScope,
                taskId,
                taskExecutionId,
                attempt,
                diff,
                Optional.empty(),
                AuditMetadata.createdBy(actorId, createdAt));
    }

    public static ReviewSubject reconstitute(
            ReviewSubjectId id,
            ReviewSubjectType type,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            ReviewDiffReference diff,
            TaskFactHash subjectHash,
            AuditMetadata audit) {
        return new ReviewSubject(
                id, type, scope, taskId, taskExecutionId, attempt, diff,
                Optional.of(Objects.requireNonNull(subjectHash, "subjectHash")), audit);
    }

    private TaskFactHash calculateHash() {
        StringBuilder canonical = new StringBuilder("review-subject-v1");
        append(canonical, type.name());
        append(canonical, scope.organizationId().toString());
        append(canonical, scope.teamId().toString());
        append(canonical, scope.workspaceId().toString());
        append(canonical, scope.projectId().toString());
        append(canonical, taskId.toString());
        append(canonical, taskExecutionId.toString());
        append(canonical, Integer.toString(attempt));
        append(canonical, diff.codingTarget().snapshotId().toString());
        append(canonical, Long.toString(diff.codingTarget().revision()));
        append(canonical, diff.codingTarget().snapshotHash().toString());
        append(canonical, diff.artifact().id().toString());
        append(canonical, diff.artifact().finalHash().toString());
        append(canonical, diff.baselineCommit().value());
        append(canonical, diff.deliveryCommit().value());
        append(canonical, diff.generation().toString());
        append(canonical, diff.manifestHash().toString());
        return TaskFactHash.sha256(canonical.toString());
    }

    static void append(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }

    public ReviewSubjectReference reference() {
        return new ReviewSubjectReference(id, type, subjectHash);
    }

    public ReviewSubjectId id() { return id; }
    public ReviewSubjectType type() { return type; }
    public WorkItemScope scope() { return scope; }
    public TaskId taskId() { return taskId; }
    public TaskExecutionId taskExecutionId() { return taskExecutionId; }
    public int attempt() { return attempt; }
    public ReviewDiffReference diff() { return diff; }
    public TaskFactHash subjectHash() { return subjectHash; }
    public AuditMetadata audit() { return audit; }
}
