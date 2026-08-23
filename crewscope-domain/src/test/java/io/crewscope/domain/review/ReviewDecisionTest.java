package io.crewscope.domain.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ReviewerEligibilityMode;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.responsibility.ReviewerPolicyViolationException;
import io.crewscope.domain.review.event.ReviewDecisionRecorded;
import io.crewscope.domain.review.event.ReviewModificationRoundStarted;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReviewDecisionTest {

    @Test
    void acceptsAllTerminalHumanGateTypesWithStrictEligibility() {
        for (ReviewDecisionType type : List.of(
                ReviewDecisionType.APPROVED,
                ReviewDecisionType.CHANGES_REQUESTED,
                ReviewDecisionType.REJECTED)) {
            GateFixture fixture = new GateFixture();
            ReviewDecision decision = fixture.decision(type, "Human conclusion: " + type);

            assertEquals(type, decision.type());
            assertEquals(ReviewerMode.GATE, decision.reviewerMode());
            assertEquals(ReviewerEligibilityMode.STRICT_SEPARATION, decision.eligibility().mode());
            assertEquals(fixture.gateReviewer.id(), decision.reviewerPrincipalId());
            assertEquals(fixture.gateMember.id(), decision.reviewerMemberId());
            assertEquals(1, decision.revision());
            assertFalse(decision.predecessorDecisionId().isPresent());

            ReviewDecisionRecorded event = ReviewDecisionRecorded.from(decision);
            assertEquals(decision.id().value(), event.decisionId());
            assertEquals(type.name(), event.decisionType());
        }
    }

    @Test
    void appendsCommentsBeforeOneTerminalDecisionAndCannotReplaceTheConclusion() {
        GateFixture fixture = new GateFixture();
        ReviewDecision comment = fixture.decision(
                ReviewDecisionType.COMMENTED, "Please double-check the null branch");
        ReviewDecision approved = fixture.successor(
                comment, ReviewDecisionType.APPROVED, "The branch is covered");

        assertEquals(2, approved.revision());
        assertEquals(comment.id(), approved.predecessorDecisionId().orElseThrow());
        assertThrows(DomainValidationException.class, () -> fixture.successor(
                approved, ReviewDecisionType.REJECTED, "Cannot replace approval"));
    }

    @Test
    void rejectsAgentUnassignedMemberAndOwnerExecutorDutyConflict() {
        GateFixture fixture = new GateFixture();

        assertThrows(DomainValidationException.class, () -> fixture.decisionAs(
                ReviewDecisionType.APPROVED,
                fixture.review.reviewerAgent,
                fixture.gateMember,
                fixture.assignments,
                "Agent cannot approve"));
        assertThrows(DomainValidationException.class, () -> fixture.decisionAs(
                ReviewDecisionType.APPROVED,
                fixture.gateReviewer,
                fixture.gateMember,
                List.of(fixture.ownerAssignment, fixture.executorAssignment),
                "Assignment is required"));

        ResponsibilityAssignment ownerReviewerAssignment = ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(),
                fixture.workItem,
                ResponsibilityRole.REVIEWER,
                fixture.review.actor,
                Optional.of(fixture.ownerMember),
                fixture.review.actor,
                ReviewDomainFixture.CREATED_AT);
        assertThrows(ReviewerPolicyViolationException.class, () -> fixture.decisionAs(
                ReviewDecisionType.APPROVED,
                fixture.review.actor,
                fixture.ownerMember,
                List.of(
                        fixture.ownerAssignment,
                        fixture.executorAssignment,
                        fixture.gateAssignment,
                        ownerReviewerAssignment),
                "Owner cannot self-approve under strict policy"));
    }

    @Test
    void requiresCompletedCurrentRequestAndExactEtag() {
        GateFixture fixture = new GateFixture();

        assertThrows(OptimisticLockConflictException.class, () -> ReviewDecision.initial(
                ReviewDecisionId.generate(),
                fixture.completedRequest,
                fixture.review.context,
                fixture.task,
                fixture.workItem,
                ReviewDecisionType.APPROVED,
                "Wrong ETag",
                1,
                ReviewerEligibilityPolicy.strict(),
                fixture.gateReviewer,
                fixture.gateMember,
                fixture.teamMembers,
                fixture.assignments,
                ReviewDomainFixture.LATER));

        ReviewRequest running = ReviewRequest.initial(
                        ReviewRequestId.generate(),
                        fixture.review.context,
                        fixture.review.actor,
                        ReviewDomainFixture.CREATED_AT)
                .start(
                        fixture.review.context,
                        0,
                        fixture.review.actor,
                        ReviewDomainFixture.LATER);
        assertThrows(DomainValidationException.class, () -> fixture.decisionFor(
                running,
                fixture.review.context,
                ReviewDecisionType.APPROVED,
                1,
                "Running request cannot be approved"));

        ContextPackage changed = fixture.review.successor(
                fixture.review.context,
                fixture.review.subject,
                fixture.review.diff,
                fixture.review.testEvidence,
                fixture.review.reviewer,
                "+return name.strip();\n");
        assertThrows(StaleReviewRequestException.class, () -> fixture.decisionFor(
                fixture.completedRequest,
                changed,
                ReviewDecisionType.APPROVED,
                2,
                "Stale request cannot be approved"));
    }

    @Test
    void tracksContinuousModificationRoundsAcrossSuccessorReviewRequests() {
        GateFixture fixture = new GateFixture();
        ReviewDecision firstChanges = fixture.decision(
                ReviewDecisionType.CHANGES_REQUESTED, "Add the missing guard");
        ReviewModificationRound firstRound = ReviewModificationRound.initial(
                ReviewModificationRoundId.generate(),
                firstChanges,
                fixture.gateReviewer,
                ReviewDomainFixture.LATER);
        assertEquals(1, firstRound.roundNumber());

        ContextPackage changed = fixture.review.successor(
                fixture.review.context,
                fixture.review.subject,
                fixture.review.diff,
                fixture.review.testEvidence,
                fixture.review.reviewer,
                "+return name == null ? \"\" : name.trim();\n");
        ReviewRequest invalidated = fixture.completedRequest.invalidate(
                changed, 2, fixture.review.actor, ReviewDomainFixture.LATER);
        ReviewRequest secondRequest = ReviewRequest.successor(
                        ReviewRequestId.generate(),
                        invalidated,
                        changed,
                        fixture.review.actor,
                        ReviewDomainFixture.LATER)
                .start(changed, 0, fixture.review.actor, ReviewDomainFixture.LATER)
                .complete(changed, 1, fixture.review.actor, ReviewDomainFixture.LATER);
        ReviewDecision secondChanges = fixture.decisionFor(
                secondRequest,
                changed,
                ReviewDecisionType.CHANGES_REQUESTED,
                2,
                "Refine the regression test");
        ReviewModificationRound secondRound = ReviewModificationRound.successor(
                ReviewModificationRoundId.generate(),
                firstRound,
                secondChanges,
                fixture.gateReviewer,
                ReviewDomainFixture.LATER);

        assertEquals(2, secondRound.roundNumber());
        assertEquals(firstRound.id(), secondRound.predecessorRoundId().orElseThrow());
        assertEquals(2, secondRound.sourceRequest().revision());
        assertEquals(
                secondRound.id().value(),
                ReviewModificationRoundStarted.from(secondRound).roundId());

        ReviewDecision approved = fixture.decisionFor(
                secondRequest,
                changed,
                ReviewDecisionType.APPROVED,
                2,
                "Approved");
        assertThrows(DomainValidationException.class, () -> ReviewModificationRound.initial(
                ReviewModificationRoundId.generate(),
                approved,
                fixture.gateReviewer,
                ReviewDomainFixture.LATER));
    }

    private static final class GateFixture {

        private final ReviewDomainFixture review = new ReviewDomainFixture();
        private final Principal gateReviewer = user("Gate reviewer");
        private final TeamMember ownerMember = TeamMember.join(
                review.subjectOwner,
                teamScope(),
                review.actor,
                TeamJoinMethod.BOOTSTRAP,
                ReviewDomainFixture.CREATED_AT);
        private final TeamMember gateMember = TeamMember.join(
                TeamMemberId.generate(),
                teamScope(),
                gateReviewer,
                TeamJoinMethod.OIDC,
                ReviewDomainFixture.CREATED_AT);
        private final Principal codingAgent = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(review.scope.organizationId(), review.scope.teamId()),
                PrincipalType.SPECIALIST_AGENT,
                Optional.of(review.actor.id()),
                "Coding Specialist",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                ReviewDomainFixture.CREATED_AT);
        private final WorkItem workItem = WorkItem.reconstitute(
                WorkItemId.generate(),
                review.scope,
                new WorkItemKey("CRW-1"),
                "Review code delivery",
                WorkItemStatus.IN_REVIEW,
                3,
                AuditMetadata.createdBy(review.actor.id(), ReviewDomainFixture.CREATED_AT));
        private final ResponsibilityAssignment ownerAssignment = ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(),
                workItem,
                ResponsibilityRole.OWNER,
                review.actor,
                Optional.of(ownerMember),
                review.actor,
                ReviewDomainFixture.CREATED_AT);
        private final ResponsibilityAssignment executorAssignment = ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(),
                workItem,
                ResponsibilityRole.EXECUTOR,
                codingAgent,
                Optional.empty(),
                review.actor,
                ReviewDomainFixture.CREATED_AT);
        private final ResponsibilityAssignment gateAssignment = ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(),
                workItem,
                ResponsibilityRole.REVIEWER,
                gateReviewer,
                Optional.of(gateMember),
                review.actor,
                ReviewDomainFixture.CREATED_AT);
        private final List<ResponsibilityAssignment> assignments =
                List.of(ownerAssignment, executorAssignment, gateAssignment);
        private final List<TeamMember> teamMembers = List.of(ownerMember, gateMember);
        private final Task task = Task.create(
                        review.taskId,
                        workItem,
                        TaskSource.fromWorkItem(workItem),
                        TaskResponsibilitySnapshot.capture(
                                workItem, assignments, ReviewDomainFixture.CREATED_AT),
                        review.actor,
                        ReviewDomainFixture.CREATED_AT)
                .switchCurrentExecution(
                        Optional.empty(),
                        review.executionId,
                        0,
                        review.actor,
                        ReviewDomainFixture.LATER);
        private final ReviewRequest completedRequest = ReviewRequest.initial(
                        ReviewRequestId.generate(),
                        review.context,
                        review.actor,
                        ReviewDomainFixture.CREATED_AT)
                .start(review.context, 0, review.actor, ReviewDomainFixture.LATER)
                .complete(review.context, 1, review.actor, ReviewDomainFixture.LATER);

        private Principal user(String name) {
            return Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.team(review.scope.organizationId(), review.scope.teamId()),
                    PrincipalType.USER,
                    Optional.empty(),
                    name,
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    ReviewDomainFixture.CREATED_AT);
        }

        private TeamScope teamScope() {
            return new TeamScope(review.scope.organizationId(), review.scope.teamId());
        }

        private ReviewDecision decision(ReviewDecisionType type, String rationale) {
            return decisionFor(completedRequest, review.context, type, 2, rationale);
        }

        private ReviewDecision successor(
                ReviewDecision predecessor, ReviewDecisionType type, String rationale) {
            return ReviewDecision.successor(
                    ReviewDecisionId.generate(),
                    predecessor,
                    completedRequest,
                    review.context,
                    task,
                    workItem,
                    type,
                    rationale,
                    2,
                    ReviewerEligibilityPolicy.strict(),
                    gateReviewer,
                    gateMember,
                    teamMembers,
                    assignments,
                    ReviewDomainFixture.LATER);
        }

        private ReviewDecision decisionFor(
                ReviewRequest request,
                ContextPackage context,
                ReviewDecisionType type,
                long expectedVersion,
                String rationale) {
            return ReviewDecision.initial(
                    ReviewDecisionId.generate(),
                    request,
                    context,
                    task,
                    workItem,
                    type,
                    rationale,
                    expectedVersion,
                    ReviewerEligibilityPolicy.strict(),
                    gateReviewer,
                    gateMember,
                    teamMembers,
                    assignments,
                    ReviewDomainFixture.LATER);
        }

        private ReviewDecision decisionAs(
                ReviewDecisionType type,
                Principal reviewer,
                TeamMember member,
                List<ResponsibilityAssignment> responsibilityFacts,
                String rationale) {
            return ReviewDecision.initial(
                    ReviewDecisionId.generate(),
                    completedRequest,
                    review.context,
                    task,
                    workItem,
                    type,
                    rationale,
                    2,
                    ReviewerEligibilityPolicy.strict(),
                    reviewer,
                    member,
                    teamMembers,
                    responsibilityFacts,
                    ReviewDomainFixture.LATER);
        }
    }
}
