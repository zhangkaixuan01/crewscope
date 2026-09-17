package io.crewscope.domain.responsibility;

import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Collection;
import java.util.Objects;

/**
 * The single rule for who currently holds a Reviewer responsibility on a WorkItem.
 *
 * <p>Two subjects can carry a REVIEWER assignment, and the Review surfaces used to test for each of
 * them in three separate places: the Gate decision service, the immutable {@code ReviewDecision}
 * authority check, and the Reviewer execution authority check. A duplicated qualification rule is
 * exactly the drift M9 warns about — a member would one day be offered a Gate action the decision
 * aggregate then refuses — so the predicates live here and every caller reads them.
 *
 * <p>The two subjects are deliberately not one predicate: a human Gate Reviewer is identified by
 * membership as well as by Principal and must be a USER, while the advisory Reviewer is an Agent
 * Principal whose owning member is recorded separately.
 */
public final class ReviewerResponsibility {

    private ReviewerResponsibility() {}

    /**
     * Whether the given member holds the current active USER Reviewer responsibility.
     *
     * <p>Membership is part of the identity on purpose: a REVIEWER assignment names both the Principal
     * and the Team member, and a Gate decision has to be attributed to the member it was granted to.
     */
    public static boolean holdsGateReviewer(
            Collection<ResponsibilityAssignment> assignments,
            PrincipalId reviewerPrincipalId,
            TeamMemberId reviewerMemberId) {
        PrincipalId requiredPrincipal =
                Objects.requireNonNull(reviewerPrincipalId, "reviewerPrincipalId");
        TeamMemberId requiredMember =
                Objects.requireNonNull(reviewerMemberId, "reviewerMemberId");
        return Objects.requireNonNull(assignments, "assignments").stream()
                .anyMatch(assignment ->
                        assignment.isActive()
                                && assignment.role() == ResponsibilityRole.REVIEWER
                                && assignment.actorType() == PrincipalType.USER
                                && assignment.actorPrincipalId().equals(requiredPrincipal)
                                && assignment.actorMemberId()
                                        .filter(requiredMember::equals)
                                        .isPresent());
    }

    /** Requires the active USER Reviewer responsibility, refusing exactly as a Gate command does. */
    public static void requireGateReviewer(
            Collection<ResponsibilityAssignment> assignments,
            PrincipalId reviewerPrincipalId,
            TeamMemberId reviewerMemberId) {
        if (!holdsGateReviewer(assignments, reviewerPrincipalId, reviewerMemberId)) {
            throw new DomainValidationException(
                    "reviewDecision.reviewerMemberId",
                    "must hold the current active USER Reviewer assignment");
        }
    }

    /** Whether the advisory Reviewer Principal still holds the current active Reviewer responsibility. */
    public static boolean holdsReviewer(
            Collection<ResponsibilityAssignment> assignments, PrincipalId reviewerPrincipalId) {
        PrincipalId requiredPrincipal =
                Objects.requireNonNull(reviewerPrincipalId, "reviewerPrincipalId");
        return Objects.requireNonNull(assignments, "assignments").stream()
                .anyMatch(assignment ->
                        assignment.isActive()
                                && assignment.role() == ResponsibilityRole.REVIEWER
                                && assignment.actorPrincipalId().equals(requiredPrincipal));
    }
}
