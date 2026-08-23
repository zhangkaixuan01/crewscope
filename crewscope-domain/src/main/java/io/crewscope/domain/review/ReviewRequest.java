package io.crewscope.domain.review;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Versioned Review workflow request bound to one exact ContextPackage authority snapshot. */
public final class ReviewRequest {

    private final ReviewRequestId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId taskExecutionId;
    private final int attempt;
    private final long revision;
    private final Optional<ReviewRequestId> predecessorRequestId;
    private final ReviewSubjectReference subject;
    private final ContextPackageReference contextPackage;
    private final ReviewDiffReference diff;
    private final ReviewTestEvidenceReference testEvidence;
    private final ReviewerExecutionReference reviewer;
    private final TaskFactHash requestHash;
    private final ReviewRequestStatus status;
    private final Optional<ReviewInvalidationReason> invalidationReason;
    private final long version;
    private final AuditMetadata audit;

    private ReviewRequest(
            ReviewRequestId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            long revision,
            Optional<ReviewRequestId> predecessorRequestId,
            ReviewSubjectReference subject,
            ContextPackageReference contextPackage,
            ReviewDiffReference diff,
            ReviewTestEvidenceReference testEvidence,
            ReviewerExecutionReference reviewer,
            Optional<TaskFactHash> expectedRequestHash,
            ReviewRequestStatus status,
            Optional<ReviewInvalidationReason> invalidationReason,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1 || revision < 1 || version < 0) {
            throw new DomainValidationException(
                    "reviewRequest.version", "attempt and revision are positive; version is non-negative");
        }
        this.attempt = attempt;
        this.revision = revision;
        this.predecessorRequestId = requirePredecessor(id, revision, predecessorRequestId);
        this.subject = Objects.requireNonNull(subject, "subject");
        this.contextPackage = Objects.requireNonNull(contextPackage, "contextPackage");
        this.diff = Objects.requireNonNull(diff, "diff");
        this.testEvidence = Objects.requireNonNull(testEvidence, "testEvidence");
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer");
        requireAuthorityLineage();
        this.requestHash = calculateHash();
        Objects.requireNonNull(expectedRequestHash, "expectedRequestHash").ifPresent(expected -> {
            if (!expected.equals(this.requestHash)) {
                throw new DomainValidationException(
                        "reviewRequest.requestHash", "must match immutable Review authority");
            }
        });
        this.status = Objects.requireNonNull(status, "status");
        this.invalidationReason = requireInvalidation(status, invalidationReason);
        this.version = version;
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public static ReviewRequest initial(
            ReviewRequestId id,
            ContextPackage context,
            Principal actor,
            UtcTimestamp createdAt) {
        return create(id, 1, Optional.empty(), context, actor, createdAt);
    }

    /** Opens the next request only after the prior exact lineage has been invalidated. */
    public static ReviewRequest successor(
            ReviewRequestId id,
            ReviewRequest predecessor,
            ContextPackage context,
            Principal actor,
            UtcTimestamp createdAt) {
        ReviewRequest prior = Objects.requireNonNull(predecessor, "predecessor");
        ContextPackage nextContext = Objects.requireNonNull(context, "context");
        if (prior.status != ReviewRequestStatus.INVALIDATED) {
            if (prior.contextPackage.equals(nextContext.reference())) {
                throw new DuplicateReviewRequestException();
            }
            throw new DomainValidationException(
                    "reviewRequest.predecessorRequestId",
                    "the prior request must be invalidated before replacement");
        }
        if (!prior.scope.equals(nextContext.scope())
                || !prior.taskId.equals(nextContext.taskId())
                || !prior.taskExecutionId.equals(nextContext.taskExecutionId())
                || prior.attempt != nextContext.attempt()) {
            throw new DomainValidationException(
                    "reviewRequest.predecessorRequestId", "must share Scope, Task and attempt");
        }
        return create(
                id,
                Math.addExact(prior.revision, 1),
                Optional.of(prior.id),
                nextContext,
                actor,
                createdAt);
    }

    private static ReviewRequest create(
            ReviewRequestId id,
            long revision,
            Optional<ReviewRequestId> predecessor,
            ContextPackage context,
            Principal actor,
            UtcTimestamp createdAt) {
        ContextPackage requiredContext = Objects.requireNonNull(context, "context");
        PrincipalId actorId = ReviewActorPolicy.requireActiveInScope(
                actor, requiredContext.scope(), "reviewRequest.createdByPrincipalId");
        return new ReviewRequest(
                id,
                requiredContext.scope(),
                requiredContext.taskId(),
                requiredContext.taskExecutionId(),
                requiredContext.attempt(),
                revision,
                predecessor,
                requiredContext.subject(),
                requiredContext.reference(),
                requiredContext.diff(),
                requiredContext.testEvidence(),
                requiredContext.reviewer(),
                Optional.empty(),
                ReviewRequestStatus.OPEN,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actorId, createdAt));
    }

    public ReviewRequest start(
            ContextPackage current,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        requireCurrent(current);
        if (status != ReviewRequestStatus.OPEN) {
            throw new DomainValidationException(
                    "reviewRequest.status", "only OPEN requests can start");
        }
        return transition(ReviewRequestStatus.IN_PROGRESS, Optional.empty(), actor, occurredAt);
    }

    public ReviewRequest complete(
            ContextPackage current,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        requireCurrent(current);
        if (status != ReviewRequestStatus.IN_PROGRESS) {
            throw new DomainValidationException(
                    "reviewRequest.status", "only IN_PROGRESS requests can complete");
        }
        return transition(ReviewRequestStatus.COMPLETED, Optional.empty(), actor, occurredAt);
    }

    /** Invalidates an old request only when current immutable authority actually drifted. */
    public ReviewRequest invalidate(
            ContextPackage current,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        if (status == ReviewRequestStatus.INVALIDATED) {
            throw new DomainValidationException(
                    "reviewRequest.status", "an invalidated request is terminal");
        }
        ReviewInvalidationReason reason = staleReason(current).orElseThrow(() ->
                new DomainValidationException(
                        "reviewRequest", "current authority has not changed"));
        return transition(ReviewRequestStatus.INVALIDATED, Optional.of(reason), actor, occurredAt);
    }

    /** Guard used before Reviewer start/resume/output persistence and later Gate commands. */
    public void requireCurrent(ContextPackage current) {
        if (status == ReviewRequestStatus.INVALIDATED) {
            throw new StaleReviewRequestException(invalidationReason.orElseThrow());
        }
        staleReason(current).ifPresent(reason -> {
            throw new StaleReviewRequestException(reason);
        });
    }

    public Optional<ReviewInvalidationReason> staleReason(ContextPackage current) {
        ContextPackage authority = Objects.requireNonNull(current, "current");
        if (!scope.equals(authority.scope())
                || !taskId.equals(authority.taskId())
                || !taskExecutionId.equals(authority.taskExecutionId())
                || attempt != authority.attempt()) {
            throw new DomainValidationException(
                    "reviewRequest", "current facts must share Scope, Task and attempt");
        }
        if (!diff.equals(authority.diff())) {
            return Optional.of(ReviewInvalidationReason.DIFF_CHANGED);
        }
        if (!subject.equals(authority.subject())) {
            return Optional.of(ReviewInvalidationReason.SUBJECT_CHANGED);
        }
        if (!testEvidence.equals(authority.testEvidence())) {
            return Optional.of(ReviewInvalidationReason.TEST_EVIDENCE_CHANGED);
        }
        ReviewerExecutionReference currentReviewer = authority.reviewer();
        if (!reviewer.templateVersion().equals(currentReviewer.templateVersion())
                || !reviewer.templateHash().equals(currentReviewer.templateHash())
                || !reviewer.configurationRevision().equals(
                        currentReviewer.configurationRevision())
                || !reviewer.configurationHash().equals(currentReviewer.configurationHash())
                || !reviewer.agentProfileId().equals(currentReviewer.agentProfileId())
                || reviewer.agentProfileVersion() != currentReviewer.agentProfileVersion()
                || !reviewer.agentPrincipalId().equals(currentReviewer.agentPrincipalId())
                || !reviewer.reviewerOwnerMemberId().equals(
                        currentReviewer.reviewerOwnerMemberId())
                || !reviewer.subjectOwnerMemberId().equals(
                        currentReviewer.subjectOwnerMemberId())
                || reviewer.relationship() != currentReviewer.relationship()) {
            return Optional.of(ReviewInvalidationReason.REVIEWER_CONFIGURATION_CHANGED);
        }
        if (!reviewer.policySnapshotId().equals(currentReviewer.policySnapshotId())
                || reviewer.policySnapshotRevision() != currentReviewer.policySnapshotRevision()
                || !reviewer.policySnapshotHash().equals(currentReviewer.policySnapshotHash())) {
            return Optional.of(ReviewInvalidationReason.POLICY_CHANGED);
        }
        if (!contextPackage.equals(authority.reference())) {
            return Optional.of(ReviewInvalidationReason.CONTEXT_CHANGED);
        }
        return Optional.empty();
    }

    private ReviewRequest transition(
            ReviewRequestStatus next,
            Optional<ReviewInvalidationReason> reason,
            Principal actor,
            UtcTimestamp occurredAt) {
        PrincipalId actorId = ReviewActorPolicy.requireActiveInScope(
                actor, scope, "reviewRequest.updatedByPrincipalId");
        return new ReviewRequest(
                id,
                scope,
                taskId,
                taskExecutionId,
                attempt,
                revision,
                predecessorRequestId,
                subject,
                contextPackage,
                diff,
                testEvidence,
                reviewer,
                Optional.of(requestHash),
                next,
                reason,
                Math.addExact(version, 1),
                audit.modifiedBy(actorId, occurredAt));
    }

    public static ReviewRequest reconstitute(
            ReviewRequestId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            long revision,
            Optional<ReviewRequestId> predecessorRequestId,
            ReviewSubjectReference subject,
            ContextPackageReference contextPackage,
            ReviewDiffReference diff,
            ReviewTestEvidenceReference testEvidence,
            ReviewerExecutionReference reviewer,
            TaskFactHash requestHash,
            ReviewRequestStatus status,
            Optional<ReviewInvalidationReason> invalidationReason,
            long version,
            AuditMetadata audit) {
        return new ReviewRequest(
                id, scope, taskId, taskExecutionId, attempt, revision, predecessorRequestId,
                subject, contextPackage, diff, testEvidence, reviewer,
                Optional.of(Objects.requireNonNull(requestHash, "requestHash")),
                status, invalidationReason, version, audit);
    }

    private void requireAuthorityLineage() {
        boolean mismatch = !scope.equals(testEvidence.scope())
                || !taskId.equals(testEvidence.taskId())
                || !taskExecutionId.equals(testEvidence.taskExecutionId())
                || attempt != testEvidence.attempt()
                || !scope.equals(reviewer.scope())
                || !taskId.equals(reviewer.taskId())
                || !taskExecutionId.equals(reviewer.taskExecutionId())
                || !diff.codingTarget().equals(testEvidence.codingTarget())
                || !diff.generation().equals(testEvidence.diffGeneration())
                || !diff.manifestHash().equals(testEvidence.diffManifestHash());
        if (mismatch) {
            throw new DomainValidationException(
                    "reviewRequest", "all fixed facts must share exact Review lineage");
        }
    }

    private TaskFactHash calculateHash() {
        StringBuilder canonical = new StringBuilder("review-request-v1");
        ReviewSubject.append(canonical, scope.organizationId().toString());
        ReviewSubject.append(canonical, scope.teamId().toString());
        ReviewSubject.append(canonical, scope.workspaceId().toString());
        ReviewSubject.append(canonical, scope.projectId().toString());
        ReviewSubject.append(canonical, taskId.toString());
        ReviewSubject.append(canonical, taskExecutionId.toString());
        ReviewSubject.append(canonical, Integer.toString(attempt));
        ReviewSubject.append(canonical, Long.toString(revision));
        ReviewSubject.append(canonical, subject.id().toString());
        ReviewSubject.append(canonical, subject.subjectHash().toString());
        ReviewSubject.append(canonical, contextPackage.id().toString());
        ReviewSubject.append(canonical, Long.toString(contextPackage.version()));
        ReviewSubject.append(canonical, contextPackage.contextHash().toString());
        ReviewSubject.append(canonical, diff.artifact().id().toString());
        ReviewSubject.append(canonical, diff.artifact().finalHash().toString());
        ReviewSubject.append(canonical, testEvidence.id().toString());
        ReviewSubject.append(canonical, testEvidence.evidenceHash().toString());
        ReviewSubject.append(canonical, reviewer.templateVersion().toString());
        ReviewSubject.append(canonical, reviewer.templateHash().toString());
        ReviewSubject.append(canonical, reviewer.configurationRevision().toString());
        ReviewSubject.append(canonical, reviewer.configurationHash().toString());
        ReviewSubject.append(canonical, reviewer.policySnapshotId().toString());
        ReviewSubject.append(canonical, Long.toString(reviewer.policySnapshotRevision()));
        ReviewSubject.append(canonical, reviewer.policySnapshotHash().toString());
        return TaskFactHash.sha256(canonical.toString());
    }

    private static Optional<ReviewRequestId> requirePredecessor(
            ReviewRequestId id, long revision, Optional<ReviewRequestId> predecessor) {
        Optional<ReviewRequestId> required = Objects.requireNonNull(
                predecessor, "predecessorRequestId");
        if ((revision == 1) == required.isPresent() || required.filter(id::equals).isPresent()) {
            throw new DomainValidationException(
                    "reviewRequest.predecessorRequestId",
                    "must be absent for revision one and identify another request afterwards");
        }
        return required;
    }

    private static Optional<ReviewInvalidationReason> requireInvalidation(
            ReviewRequestStatus status, Optional<ReviewInvalidationReason> reason) {
        Optional<ReviewInvalidationReason> required = Objects.requireNonNull(
                reason, "invalidationReason");
        if ((status == ReviewRequestStatus.INVALIDATED) != required.isPresent()) {
            throw new DomainValidationException(
                    "reviewRequest.invalidationReason",
                    "must be present exactly for INVALIDATED status");
        }
        return required;
    }

    void requireVersion(long expected) {
        if (version != expected) {
            throw new OptimisticLockConflictException("ReviewRequest", id, expected, version);
        }
    }

    public ReviewRequestId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public TaskId taskId() { return taskId; }
    public TaskExecutionId taskExecutionId() { return taskExecutionId; }
    public int attempt() { return attempt; }
    public long revision() { return revision; }
    public Optional<ReviewRequestId> predecessorRequestId() { return predecessorRequestId; }
    public ReviewSubjectReference subject() { return subject; }
    public ContextPackageReference contextPackage() { return contextPackage; }
    public ReviewDiffReference diff() { return diff; }
    public ReviewTestEvidenceReference testEvidence() { return testEvidence; }
    public ReviewerExecutionReference reviewer() { return reviewer; }
    public TaskFactHash requestHash() { return requestHash; }
    public ReviewRequestStatus status() { return status; }
    public Optional<ReviewInvalidationReason> invalidationReason() { return invalidationReason; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
