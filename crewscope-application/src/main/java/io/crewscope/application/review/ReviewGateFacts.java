package io.crewscope.application.review;

import io.crewscope.application.availability.TransitionRemedy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.responsibility.ReviewerPolicyViolationException;
import io.crewscope.domain.responsibility.ReviewerResponsibility;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workitem.WorkItem;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the four Review Gate commands read before they allow or refuse an action.
 *
 * <p>The facts are deliberately the same facts the commands read, resolved through the same rules:
 * {@link ReviewerResponsibility} answers both Reviewer qualifications and
 * {@link ReviewerEligibilityPolicy#evaluateGate} answers duty separation. Nothing here restates a
 * rule — {@link #resolve} runs the real policy and records its verdict, which is what keeps a
 * disabled control and a rejected command from ever disagreeing.
 *
 * <p>Some preconditions are deliberately absent because they are not facts about the ReviewRequest:
 * every Gate command re-reads the current ContextPackage authority, and Execute additionally needs an
 * exact active Reviewer Specialist Session while Re-review needs the current Diff, TestEvidence and
 * PolicySnapshot. Those live in other aggregates, so a projection may offer an action on facts that
 * allow it while the command still refuses on a missing or drifted authority. The gap is recorded in
 * {@code docs/api/M9-状态流转可用性API契约.md} rather than papered over with a fabricated fact.
 */
public record ReviewGateFacts(
        ReviewRequestStatus status,
        boolean reviewerAgentAssigned,
        boolean gateReviewerAssigned,
        boolean dutySeparated,
        Optional<TransitionRemedy> reviewerRemedy) {

    public ReviewGateFacts {
        status = Objects.requireNonNull(status, "status");
        reviewerRemedy = Objects.requireNonNull(reviewerRemedy, "reviewerRemedy");
    }

    /**
     * Reads the Gate facts for one ReviewRequest from already-loaded aggregates.
     *
     * <p>Duty separation is answered by running the effective policy and reading its refusal, never by
     * re-deriving Owner/Executor overlap here. A policy that refuses for any other reason is recorded
     * as unseparated too: the projection fails closed, and the command still owns the real verdict.
     */
    public static ReviewGateFacts resolve(
            ReviewRequest request,
            Principal actor,
            Optional<TeamMember> actorMember,
            Optional<Principal> reviewerAgent,
            WorkItem workItem,
            List<TeamMember> teamMembers,
            List<ResponsibilityAssignment> assignments,
            ReviewerEligibilityPolicy eligibility) {
        ReviewRequest required = Objects.requireNonNull(request, "request");
        WorkItem item = Objects.requireNonNull(workItem, "workItem");
        boolean reviewerAgentAssigned = reviewerAgent
                .filter(Principal::canAct)
                .filter(value -> value.type().isAgent())
                .map(value -> ReviewerResponsibility.holdsReviewer(assignments, value.id()))
                .orElse(false);
        boolean gateReviewerAssigned = actorMember
                .map(member -> ReviewerResponsibility.holdsGateReviewer(
                        assignments, actor.id(), member.id()))
                .orElse(false);
        return new ReviewGateFacts(
                required.status(),
                reviewerAgentAssigned,
                gateReviewerAssigned,
                dutySeparated(
                        actor, actorMember, item, teamMembers, assignments, eligibility),
                Optional.of(ReviewWorkbenchCoordinates.assignReviewer(item)));
    }

    private static boolean dutySeparated(
            Principal actor,
            Optional<TeamMember> actorMember,
            WorkItem workItem,
            List<TeamMember> teamMembers,
            List<ResponsibilityAssignment> assignments,
            ReviewerEligibilityPolicy eligibility) {
        if (actorMember.isEmpty()) {
            return false;
        }
        try {
            Objects.requireNonNull(eligibility, "eligibility")
                    .evaluateGate(
                            workItem,
                            Objects.requireNonNull(actor, "actor"),
                            actorMember.orElseThrow(),
                            teamMembers,
                            assignments);
            return true;
        } catch (ReviewerPolicyViolationException conflict) {
            return false;
        } catch (DomainException refused) {
            return false;
        }
    }
}
