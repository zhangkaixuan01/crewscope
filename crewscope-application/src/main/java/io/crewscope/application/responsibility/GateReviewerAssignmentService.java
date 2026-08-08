package io.crewscope.application.responsibility;

import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ReviewerEligibilityDecision;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workitem.WorkItem;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Assigns human Gate Reviewers only after server-side membership and duty policy evaluation. */
public final class GateReviewerAssignmentService {

    private final ResponsibilityAssignmentRepository assignmentRepository;
    private final TeamMembershipQuery teamMembershipQuery;
    private final TransactionExecutor transactionExecutor;
    private final TimeProvider timeProvider;

    public GateReviewerAssignmentService(
            ResponsibilityAssignmentRepository assignmentRepository,
            TeamMembershipQuery teamMembershipQuery,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider) {
        this.assignmentRepository =
                Objects.requireNonNull(assignmentRepository, "assignmentRepository");
        this.teamMembershipQuery =
                Objects.requireNonNull(teamMembershipQuery, "teamMembershipQuery");
        this.transactionExecutor =
                Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Creates a Gate Reviewer fact and returns the exact policy evidence used by the command. */
    public GateReviewerAssignment assignGateReviewer(
            WorkItem workItem,
            Principal reviewer,
            TeamMember reviewerMember,
            Principal assignedBy,
            ReviewerEligibilityPolicy policy) {
        WorkItem requiredWorkItem = Objects.requireNonNull(workItem, "workItem");
        Principal requiredReviewer = Objects.requireNonNull(reviewer, "reviewer");
        TeamMember requiredMember = Objects.requireNonNull(reviewerMember, "reviewerMember");
        Principal requiredAssigner = Objects.requireNonNull(assignedBy, "assignedBy");
        ReviewerEligibilityPolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        return transactionExecutor.required(() -> assignInTransaction(
                requiredWorkItem,
                requiredReviewer,
                requiredMember,
                requiredAssigner,
                requiredPolicy,
                timeProvider.now()));
    }

    private GateReviewerAssignment assignInTransaction(
            WorkItem workItem,
            Principal reviewer,
            TeamMember reviewerMember,
            Principal assignedBy,
            ReviewerEligibilityPolicy policy,
            UtcTimestamp occurredAt) {
        assignmentRepository.lockResponsibilityChain(
                workItem.scope().organizationId(), workItem.id());
        List<TeamMember> members = List.copyOf(teamMembershipQuery.findByTeam(
                workItem.scope().organizationId(), workItem.scope().teamId()));
        List<ResponsibilityAssignment> assignments = List.copyOf(
                assignmentRepository.findActiveByWorkItem(
                        workItem.scope().organizationId(), workItem.id()));
        ReviewerEligibilityDecision decision = policy.evaluateGate(
                workItem, reviewer, reviewerMember, members, assignments);
        assignmentRepository
                .findActive(
                        workItem.scope().organizationId(),
                        workItem.id(),
                        ResponsibilityRole.REVIEWER,
                        reviewer.id())
                .ifPresent(existing -> {
                    throw new DomainValidationException(
                            "responsibilityAssignment.actorPrincipalId",
                            "already has an active REVIEWER assignment");
                });
        ResponsibilityAssignment candidate = ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(),
                workItem,
                ResponsibilityRole.REVIEWER,
                reviewer,
                Optional.of(reviewerMember),
                assignedBy,
                occurredAt);
        return new GateReviewerAssignment(
                assignmentRepository.create(candidate), decision);
    }
}
