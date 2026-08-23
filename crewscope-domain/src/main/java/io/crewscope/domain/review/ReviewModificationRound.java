package io.crewscope.domain.review;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Continuous count of human CHANGES_REQUESTED conclusions for one Task Review lineage. */
public final class ReviewModificationRound {

    private final ReviewModificationRoundId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final long roundNumber;
    private final Optional<ReviewModificationRoundId> predecessorRoundId;
    private final ReviewRequestReference sourceRequest;
    private final ReviewDecisionReference triggerDecision;
    private final TaskFactHash roundHash;
    private final AuditMetadata audit;

    private ReviewModificationRound(
            ReviewModificationRoundId id,
            WorkItemScope scope,
            TaskId taskId,
            long roundNumber,
            Optional<ReviewModificationRoundId> predecessorRoundId,
            ReviewRequestReference sourceRequest,
            ReviewDecisionReference triggerDecision,
            Optional<TaskFactHash> expectedHash,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        if (roundNumber < 1) {
            throw new DomainValidationException(
                    "reviewModificationRound.roundNumber", "must be positive");
        }
        this.roundNumber = roundNumber;
        this.predecessorRoundId = requirePredecessor(id, roundNumber, predecessorRoundId);
        this.sourceRequest = Objects.requireNonNull(sourceRequest, "sourceRequest");
        if (!this.scope.equals(this.sourceRequest.scope())
                || !this.taskId.equals(this.sourceRequest.taskId())) {
            throw new DomainValidationException(
                    "reviewModificationRound.sourceRequest",
                    "must belong to the modification-round scope and Task");
        }
        this.triggerDecision = Objects.requireNonNull(triggerDecision, "triggerDecision");
        if (this.triggerDecision.type() != ReviewDecisionType.CHANGES_REQUESTED
                || !this.triggerDecision.reviewRequest().equals(this.sourceRequest)) {
            throw new DomainValidationException(
                    "reviewModificationRound.triggerDecision",
                    "must be CHANGES_REQUESTED for the exact source ReviewRequest");
        }
        this.roundHash = calculateHash();
        Objects.requireNonNull(expectedHash, "expectedHash").ifPresent(expected -> {
            if (!expected.equals(this.roundHash)) {
                throw new DomainValidationException(
                        "reviewModificationRound.roundHash", "must match exact Review authority");
            }
        });
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public static ReviewModificationRound initial(
            ReviewModificationRoundId id,
            ReviewDecision changesRequested,
            Principal actor,
            UtcTimestamp occurredAt) {
        ReviewDecision decision = requireDecisionActor(changesRequested, actor);
        return new ReviewModificationRound(
                id,
                decision.scope(),
                decision.taskId(),
                1,
                Optional.empty(),
                decision.reviewRequest(),
                decision.reference(),
                Optional.empty(),
                AuditMetadata.createdBy(decision.reviewerPrincipalId(), occurredAt));
    }

    public static ReviewModificationRound successor(
            ReviewModificationRoundId id,
            ReviewModificationRound predecessor,
            ReviewDecision changesRequested,
            Principal actor,
            UtcTimestamp occurredAt) {
        ReviewModificationRound prior = Objects.requireNonNull(predecessor, "predecessor");
        ReviewDecision decision = requireDecisionActor(changesRequested, actor);
        if (!prior.scope.equals(decision.scope())
                || !prior.taskId.equals(decision.taskId())
                || decision.reviewRequest().revision()
                        != Math.addExact(prior.sourceRequest.revision(), 1)) {
            throw new DomainValidationException(
                    "reviewModificationRound.sourceRequest",
                    "must use the next ReviewRequest revision in the same Task lineage");
        }
        return new ReviewModificationRound(
                id,
                prior.scope,
                prior.taskId,
                Math.addExact(prior.roundNumber, 1),
                Optional.of(prior.id),
                decision.reviewRequest(),
                decision.reference(),
                Optional.empty(),
                AuditMetadata.createdBy(decision.reviewerPrincipalId(), occurredAt));
    }

    public static ReviewModificationRound reconstitute(
            ReviewModificationRoundId id,
            WorkItemScope scope,
            TaskId taskId,
            long roundNumber,
            Optional<ReviewModificationRoundId> predecessorRoundId,
            ReviewRequestReference sourceRequest,
            ReviewDecisionReference triggerDecision,
            TaskFactHash roundHash,
            AuditMetadata audit) {
        return new ReviewModificationRound(
                id,
                scope,
                taskId,
                roundNumber,
                predecessorRoundId,
                sourceRequest,
                triggerDecision,
                Optional.of(Objects.requireNonNull(roundHash, "roundHash")),
                audit);
    }

    private static ReviewDecision requireDecisionActor(
            ReviewDecision changesRequested, Principal actor) {
        ReviewDecision decision = Objects.requireNonNull(changesRequested, "changesRequested");
        Principal requiredActor = Objects.requireNonNull(actor, "actor");
        PrincipalId actorId = ReviewActorPolicy.requireActiveInScope(
                requiredActor, decision.scope(), "reviewModificationRound.createdByPrincipalId");
        if (decision.type() != ReviewDecisionType.CHANGES_REQUESTED
                || !decision.reviewerPrincipalId().equals(actorId)) {
            throw new DomainValidationException(
                    "reviewModificationRound.triggerDecision",
                    "must be created by the member who requested changes");
        }
        return decision;
    }

    private TaskFactHash calculateHash() {
        StringBuilder canonical = new StringBuilder("review-modification-round-v1");
        ReviewSubject.append(canonical, scope.organizationId().toString());
        ReviewSubject.append(canonical, scope.teamId().toString());
        ReviewSubject.append(canonical, scope.workspaceId().toString());
        ReviewSubject.append(canonical, scope.projectId().toString());
        ReviewSubject.append(canonical, taskId.toString());
        ReviewSubject.append(canonical, Long.toString(roundNumber));
        ReviewSubject.append(canonical, sourceRequest.id().toString());
        ReviewSubject.append(canonical, Long.toString(sourceRequest.revision()));
        ReviewSubject.append(canonical, Long.toString(sourceRequest.version()));
        ReviewSubject.append(canonical, sourceRequest.requestHash().toString());
        ReviewSubject.append(canonical, triggerDecision.id().toString());
        ReviewSubject.append(canonical, triggerDecision.decisionHash().toString());
        return TaskFactHash.sha256(canonical.toString());
    }

    private static Optional<ReviewModificationRoundId> requirePredecessor(
            ReviewModificationRoundId id,
            long roundNumber,
            Optional<ReviewModificationRoundId> predecessor) {
        Optional<ReviewModificationRoundId> required = Objects.requireNonNull(
                predecessor, "predecessorRoundId");
        if ((roundNumber == 1) == required.isPresent() || required.filter(id::equals).isPresent()) {
            throw new DomainValidationException(
                    "reviewModificationRound.predecessorRoundId",
                    "must be absent for round one and identify another round afterwards");
        }
        return required;
    }

    public ReviewModificationRoundId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public TaskId taskId() { return taskId; }
    public long roundNumber() { return roundNumber; }
    public Optional<ReviewModificationRoundId> predecessorRoundId() { return predecessorRoundId; }
    public ReviewRequestReference sourceRequest() { return sourceRequest; }
    public ReviewDecisionReference triggerDecision() { return triggerDecision; }
    public TaskFactHash roundHash() { return roundHash; }
    public AuditMetadata audit() { return audit; }
}
