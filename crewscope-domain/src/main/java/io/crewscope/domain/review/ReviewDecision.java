package io.crewscope.domain.review;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ReviewerEligibilityDecision;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Append-only Gate command issued only by an eligible assigned human TeamMember. */
public final class ReviewDecision {

    public static final int MAX_RATIONALE_LENGTH = 4_000;

    private final ReviewDecisionId id;
    private final WorkItemScope scope;
    private final WorkItemId workItemId;
    private final TaskId taskId;
    private final ReviewRequestReference reviewRequest;
    private final long revision;
    private final Optional<ReviewDecisionId> predecessorDecisionId;
    private final ReviewerMode reviewerMode;
    private final PrincipalId reviewerPrincipalId;
    private final TeamMemberId reviewerMemberId;
    private final ReviewerEligibilityDecision eligibility;
    private final ReviewDecisionType type;
    private final String rationale;
    private final TaskFactHash decisionHash;
    private final AuditMetadata audit;

    private ReviewDecision(
            ReviewDecisionId id,
            WorkItemScope scope,
            WorkItemId workItemId,
            TaskId taskId,
            ReviewRequestReference reviewRequest,
            long revision,
            Optional<ReviewDecisionId> predecessorDecisionId,
            ReviewerMode reviewerMode,
            PrincipalId reviewerPrincipalId,
            TeamMemberId reviewerMemberId,
            ReviewerEligibilityDecision eligibility,
            ReviewDecisionType type,
            String rationale,
            Optional<TaskFactHash> expectedHash,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.workItemId = Objects.requireNonNull(workItemId, "workItemId");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.reviewRequest = Objects.requireNonNull(reviewRequest, "reviewRequest");
        if (!this.scope.equals(this.reviewRequest.scope())
                || !this.taskId.equals(this.reviewRequest.taskId())) {
            throw new DomainValidationException(
                    "reviewDecision.reviewRequest", "must belong to the Decision scope and Task");
        }
        if (revision < 1) {
            throw new DomainValidationException("reviewDecision.revision", "must be positive");
        }
        this.revision = revision;
        this.predecessorDecisionId = requirePredecessor(id, revision, predecessorDecisionId);
        this.reviewerMode = Objects.requireNonNull(reviewerMode, "reviewerMode");
        if (this.reviewerMode != ReviewerMode.GATE) {
            throw new DomainValidationException(
                    "reviewDecision.reviewerMode", "member Decisions are always GATE");
        }
        this.reviewerPrincipalId = Objects.requireNonNull(
                reviewerPrincipalId, "reviewerPrincipalId");
        this.reviewerMemberId = Objects.requireNonNull(reviewerMemberId, "reviewerMemberId");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.type = Objects.requireNonNull(type, "type");
        this.rationale = ReviewTextPolicy.requireText(
                rationale, "reviewDecision.rationale", MAX_RATIONALE_LENGTH);
        this.decisionHash = calculateHash();
        Objects.requireNonNull(expectedHash, "expectedHash").ifPresent(expected -> {
            if (!expected.equals(this.decisionHash)) {
                throw new DomainValidationException(
                        "reviewDecision.decisionHash", "must match exact Gate authority");
            }
        });
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Creates the first comment or terminal Gate conclusion for a completed current request. */
    public static ReviewDecision initial(
            ReviewDecisionId id,
            ReviewRequest request,
            ContextPackage context,
            Task task,
            WorkItem workItem,
            ReviewDecisionType type,
            String rationale,
            long expectedRequestVersion,
            ReviewerEligibilityPolicy eligibilityPolicy,
            Principal reviewer,
            TeamMember reviewerMember,
            Collection<TeamMember> teamMembers,
            Collection<ResponsibilityAssignment> assignments,
            UtcTimestamp decidedAt) {
        DecisionAuthority authority = requireAuthority(
                request,
                context,
                task,
                workItem,
                expectedRequestVersion,
                eligibilityPolicy,
                reviewer,
                reviewerMember,
                teamMembers,
                assignments);
        return create(id, 1, Optional.empty(), authority, type, rationale, decidedAt);
    }

    /** Appends after COMMENTED; a terminal Gate conclusion cannot be replaced. */
    public static ReviewDecision successor(
            ReviewDecisionId id,
            ReviewDecision predecessor,
            ReviewRequest request,
            ContextPackage context,
            Task task,
            WorkItem workItem,
            ReviewDecisionType type,
            String rationale,
            long expectedRequestVersion,
            ReviewerEligibilityPolicy eligibilityPolicy,
            Principal reviewer,
            TeamMember reviewerMember,
            Collection<TeamMember> teamMembers,
            Collection<ResponsibilityAssignment> assignments,
            UtcTimestamp decidedAt) {
        ReviewDecision prior = Objects.requireNonNull(predecessor, "predecessor");
        if (prior.type.isTerminalGate()) {
            throw new DomainValidationException(
                    "reviewDecision.predecessorDecisionId",
                    "a terminal Gate conclusion cannot be replaced");
        }
        DecisionAuthority authority = requireAuthority(
                request,
                context,
                task,
                workItem,
                expectedRequestVersion,
                eligibilityPolicy,
                reviewer,
                reviewerMember,
                teamMembers,
                assignments);
        if (!prior.scope.equals(authority.request().scope())
                || !prior.workItemId.equals(authority.task().workItemId())
                || !prior.taskId.equals(authority.request().taskId())
                || !prior.reviewRequest.equals(ReviewRequestReference.from(authority.request()))) {
            throw new DomainValidationException(
                    "reviewDecision.predecessorDecisionId",
                    "must belong to the same current ReviewRequest");
        }
        return create(
                id,
                Math.addExact(prior.revision, 1),
                Optional.of(prior.id),
                authority,
                type,
                rationale,
                decidedAt);
    }

    private static ReviewDecision create(
            ReviewDecisionId id,
            long revision,
            Optional<ReviewDecisionId> predecessor,
            DecisionAuthority authority,
            ReviewDecisionType type,
            String rationale,
            UtcTimestamp decidedAt) {
        return new ReviewDecision(
                id,
                authority.request().scope(),
                authority.task().workItemId(),
                authority.request().taskId(),
                ReviewRequestReference.from(authority.request()),
                revision,
                predecessor,
                ReviewerMode.GATE,
                authority.reviewer().id(),
                authority.reviewerMember().id(),
                authority.eligibility(),
                type,
                rationale,
                Optional.empty(),
                AuditMetadata.createdBy(authority.reviewer().id(), decidedAt));
    }

    /** Reconstitutes an immutable Gate decision and verifies its canonical hash. */
    public static ReviewDecision reconstitute(
            ReviewDecisionId id,
            WorkItemScope scope,
            WorkItemId workItemId,
            TaskId taskId,
            ReviewRequestReference reviewRequest,
            long revision,
            Optional<ReviewDecisionId> predecessorDecisionId,
            ReviewerMode reviewerMode,
            PrincipalId reviewerPrincipalId,
            TeamMemberId reviewerMemberId,
            ReviewerEligibilityDecision eligibility,
            ReviewDecisionType type,
            String rationale,
            TaskFactHash decisionHash,
            AuditMetadata audit) {
        return new ReviewDecision(
                id,
                scope,
                workItemId,
                taskId,
                reviewRequest,
                revision,
                predecessorDecisionId,
                reviewerMode,
                reviewerPrincipalId,
                reviewerMemberId,
                eligibility,
                type,
                rationale,
                Optional.of(Objects.requireNonNull(decisionHash, "decisionHash")),
                audit);
    }

    private static DecisionAuthority requireAuthority(
            ReviewRequest request,
            ContextPackage context,
            Task task,
            WorkItem workItem,
            long expectedRequestVersion,
            ReviewerEligibilityPolicy eligibilityPolicy,
            Principal reviewer,
            TeamMember reviewerMember,
            Collection<TeamMember> teamMembers,
            Collection<ResponsibilityAssignment> assignments) {
        ReviewRequest requiredRequest = Objects.requireNonNull(request, "request");
        ContextPackage requiredContext = Objects.requireNonNull(context, "context");
        requiredRequest.requireVersion(expectedRequestVersion);
        requiredRequest.requireCurrent(requiredContext);
        if (requiredRequest.status() != ReviewRequestStatus.COMPLETED) {
            throw new DomainValidationException(
                    "reviewDecision.reviewRequest", "must reference a COMPLETED current request");
        }
        Task requiredTask = Objects.requireNonNull(task, "task");
        WorkItem requiredWorkItem = Objects.requireNonNull(workItem, "workItem");
        if (!requiredTask.scope().equals(requiredRequest.scope())
                || !requiredTask.id().equals(requiredRequest.taskId())
                || !requiredTask.workItemId().equals(requiredWorkItem.id())
                || !requiredWorkItem.scope().equals(requiredRequest.scope())
                || requiredTask.currentExecutionId()
                        .filter(requiredRequest.taskExecutionId()::equals)
                        .isEmpty()) {
            throw new DomainValidationException(
                    "reviewDecision.reviewRequest",
                    "must share the exact WorkItem, Task and current TaskExecution");
        }
        Principal requiredReviewer = Objects.requireNonNull(reviewer, "reviewer");
        TeamMember requiredMember = Objects.requireNonNull(reviewerMember, "reviewerMember");
        List<ResponsibilityAssignment> requiredAssignments = List.copyOf(
                Objects.requireNonNull(assignments, "assignments"));
        ReviewerEligibilityDecision eligibility = Objects.requireNonNull(
                        eligibilityPolicy, "eligibilityPolicy")
                .evaluateGate(
                        requiredWorkItem,
                        requiredReviewer,
                        requiredMember,
                        teamMembers,
                        requiredAssignments);
        boolean assignedGateReviewer = requiredAssignments.stream().anyMatch(assignment ->
                assignment.isActive()
                        && assignment.role() == ResponsibilityRole.REVIEWER
                        && assignment.actorType() == PrincipalType.USER
                        && assignment.actorPrincipalId().equals(requiredReviewer.id())
                        && assignment.actorMemberId().filter(requiredMember.id()::equals).isPresent());
        if (!assignedGateReviewer) {
            throw new DomainValidationException(
                    "reviewDecision.reviewerMemberId",
                    "must hold the current active USER Reviewer assignment");
        }
        return new DecisionAuthority(
                requiredRequest, requiredTask, requiredReviewer, requiredMember, eligibility);
    }

    private TaskFactHash calculateHash() {
        StringBuilder canonical = new StringBuilder("review-decision-v1");
        ReviewSubject.append(canonical, scope.organizationId().toString());
        ReviewSubject.append(canonical, scope.teamId().toString());
        ReviewSubject.append(canonical, scope.workspaceId().toString());
        ReviewSubject.append(canonical, scope.projectId().toString());
        ReviewSubject.append(canonical, workItemId.toString());
        ReviewSubject.append(canonical, taskId.toString());
        ReviewSubject.append(canonical, reviewRequest.id().toString());
        ReviewSubject.append(canonical, Long.toString(reviewRequest.revision()));
        ReviewSubject.append(canonical, Long.toString(reviewRequest.version()));
        ReviewSubject.append(canonical, reviewRequest.requestHash().toString());
        ReviewSubject.append(canonical, reviewRequest.contextPackage().contextHash().toString());
        ReviewSubject.append(canonical, Long.toString(revision));
        ReviewSubject.append(canonical, reviewerPrincipalId.toString());
        ReviewSubject.append(canonical, reviewerMemberId.toString());
        ReviewSubject.append(canonical, eligibility.mode().name());
        eligibility.conflictingRoles().stream()
                .sorted(Comparator.comparing(Enum::name))
                .forEach(role -> ReviewSubject.append(canonical, role.name()));
        eligibility.policyPack().ifPresent(policy -> {
            ReviewSubject.append(canonical, policy.id().toString());
            ReviewSubject.append(canonical, Long.toString(policy.version()));
        });
        ReviewSubject.append(canonical, eligibility.overrideReason().orElse("override:none"));
        ReviewSubject.append(canonical, type.name());
        ReviewSubject.append(canonical, rationale);
        return TaskFactHash.sha256(canonical.toString());
    }

    private static Optional<ReviewDecisionId> requirePredecessor(
            ReviewDecisionId id,
            long revision,
            Optional<ReviewDecisionId> predecessor) {
        Optional<ReviewDecisionId> required = Objects.requireNonNull(
                predecessor, "predecessorDecisionId");
        if ((revision == 1) == required.isPresent() || required.filter(id::equals).isPresent()) {
            throw new DomainValidationException(
                    "reviewDecision.predecessorDecisionId",
                    "must be absent for revision one and identify another Decision afterwards");
        }
        return required;
    }

    public ReviewDecisionReference reference() {
        return new ReviewDecisionReference(id, revision, reviewRequest, type, decisionHash);
    }

    public boolean isTerminalGate() { return type.isTerminalGate(); }
    public ReviewDecisionId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public WorkItemId workItemId() { return workItemId; }
    public TaskId taskId() { return taskId; }
    public ReviewRequestReference reviewRequest() { return reviewRequest; }
    public long revision() { return revision; }
    public Optional<ReviewDecisionId> predecessorDecisionId() { return predecessorDecisionId; }
    public ReviewerMode reviewerMode() { return reviewerMode; }
    public PrincipalId reviewerPrincipalId() { return reviewerPrincipalId; }
    public TeamMemberId reviewerMemberId() { return reviewerMemberId; }
    public ReviewerEligibilityDecision eligibility() { return eligibility; }
    public ReviewDecisionType type() { return type; }
    public String rationale() { return rationale; }
    public TaskFactHash decisionHash() { return decisionHash; }
    public AuditMetadata audit() { return audit; }
}

record DecisionAuthority(
        ReviewRequest request,
        Task task,
        Principal reviewer,
        TeamMember reviewerMember,
        ReviewerEligibilityDecision eligibility) {}
