package io.crewscope.application.responsibility;

import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ReviewerEligibilityDecision;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Gate Reviewer responsibility together with policy evidence for event and audit publication. */
public record GateReviewerAssignment(
        ResponsibilityAssignment assignment,
        ReviewerEligibilityDecision eligibility) {

    public GateReviewerAssignment {
        assignment = Objects.requireNonNull(assignment, "assignment");
        eligibility = Objects.requireNonNull(eligibility, "eligibility");
        if (!assignment.isActive()
                || assignment.role() != ResponsibilityRole.REVIEWER
                || assignment.actorType() != PrincipalType.USER
                || assignment.actorMemberId().isEmpty()) {
            throw new DomainValidationException(
                    "gateReviewerAssignment.assignment",
                    "must be an active human REVIEWER responsibility");
        }
    }
}
