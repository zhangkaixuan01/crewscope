package io.crewscope.domain.responsibility;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItem;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Evaluates human Gate Reviewer qualification and Owner/Executor duty separation. */
public final class ReviewerEligibilityPolicy {

    private final Optional<PolicyPackReference> singleMemberOverridePolicyPack;
    private final Optional<String> singleMemberOverrideReason;

    private ReviewerEligibilityPolicy(
            Optional<PolicyPackReference> singleMemberOverridePolicyPack,
            Optional<String> singleMemberOverrideReason) {
        this.singleMemberOverridePolicyPack =
                Objects.requireNonNull(
                        singleMemberOverridePolicyPack, "singleMemberOverridePolicyPack");
        this.singleMemberOverrideReason = normalizeOverrideReason(singleMemberOverrideReason);
        if (this.singleMemberOverridePolicyPack.isPresent()
                != this.singleMemberOverrideReason.isPresent()) {
            throw new DomainValidationException(
                    "reviewerEligibilityPolicy.singleMemberOverride",
                    "requires both PolicyPack and reason");
        }
    }

    /** Creates the default policy that never relaxes Owner/Executor duty separation. */
    public static ReviewerEligibilityPolicy strict() {
        return new ReviewerEligibilityPolicy(Optional.empty(), Optional.empty());
    }

    /** Creates an explicit PolicyPack-backed exception usable only by a one-member Team. */
    public static ReviewerEligibilityPolicy withSingleMemberOverride(
            PolicyPackReference policyPack, String reason) {
        return new ReviewerEligibilityPolicy(
                Optional.of(Objects.requireNonNull(policyPack, "policyPack")),
                Optional.ofNullable(reason));
    }

    /**
     * Returns audit evidence for an eligible human Gate Reviewer or fails with a stable policy
     * denial when active Owner/Executor responsibilities overlap.
     */
    public ReviewerEligibilityDecision evaluateGate(
            WorkItem workItem,
            Principal reviewer,
            TeamMember reviewerMember,
            Collection<TeamMember> teamMembers,
            Collection<ResponsibilityAssignment> assignments) {
        WorkItem requiredWorkItem = Objects.requireNonNull(workItem, "workItem");
        Principal requiredReviewer = requireReviewer(reviewer, reviewerMember, requiredWorkItem);
        TeamMember requiredMember = Objects.requireNonNull(reviewerMember, "reviewerMember");
        List<TeamMember> members = requireTeamMembers(teamMembers, requiredWorkItem);
        int activeMemberCount = Math.toIntExact(
                members.stream().filter(TeamMember::canParticipate).count());
        boolean reviewerIsActiveMember = members.stream()
                .filter(TeamMember::canParticipate)
                .anyMatch(member -> member.id().equals(requiredMember.id())
                        && member.userPrincipalId().equals(requiredReviewer.id()));
        if (!reviewerIsActiveMember) {
            throw new DomainValidationException(
                    "reviewerEligibilityPolicy.teamMembers",
                    "must contain the active Reviewer membership");
        }

        Set<ResponsibilityRole> conflicts = findConflicts(
                assignments, requiredWorkItem, requiredReviewer, requiredMember);
        if (conflicts.isEmpty()) {
            return ReviewerEligibilityDecision.strict();
        }
        boolean soleActiveReviewer = activeMemberCount == 1;
        if (singleMemberOverridePolicyPack.isPresent() && soleActiveReviewer) {
            return ReviewerEligibilityDecision.singleMemberOverride(
                    conflicts,
                    singleMemberOverridePolicyPack.orElseThrow(),
                    singleMemberOverrideReason.orElseThrow());
        }
        throw new ReviewerPolicyViolationException(
                requiredWorkItem.id(),
                requiredMember.id(),
                conflicts,
                activeMemberCount,
                singleMemberOverridePolicyPack.isPresent());
    }

    private static Principal requireReviewer(
            Principal reviewer, TeamMember reviewerMember, WorkItem workItem) {
        Principal requiredReviewer = Objects.requireNonNull(reviewer, "reviewer");
        TeamMember requiredMember = Objects.requireNonNull(reviewerMember, "reviewerMember");
        if (requiredReviewer.type() != PrincipalType.USER || !requiredReviewer.canAct()) {
            throw new DomainValidationException(
                    "reviewerEligibilityPolicy.reviewerPrincipalId",
                    "must reference an active USER Principal");
        }
        boolean differentTeam = requiredReviewer.scope().teamId().isPresent()
                && requiredReviewer
                        .scope()
                        .teamId()
                        .filter(workItem.scope().teamId()::equals)
                        .isEmpty();
        if (!requiredReviewer
                        .scope()
                        .organizationId()
                        .equals(workItem.scope().organizationId())
                || differentTeam
                || !requiredMember.canParticipate()
                || !requiredMember.userPrincipalId().equals(requiredReviewer.id())
                || !requiredMember
                        .scope()
                        .organizationId()
                        .equals(workItem.scope().organizationId())
                || !requiredMember.scope().teamId().equals(workItem.scope().teamId())) {
            throw new DomainValidationException(
                    "reviewerEligibilityPolicy.reviewerMemberId",
                    "must reference the Reviewer's active membership in the WorkItem Team");
        }
        return requiredReviewer;
    }

    private static List<TeamMember> requireTeamMembers(
            Collection<TeamMember> teamMembers, WorkItem workItem) {
        List<TeamMember> required = List.copyOf(Objects.requireNonNull(teamMembers, "teamMembers"));
        Set<TeamMemberId> ids = new HashSet<>();
        Set<PrincipalId> principals = new HashSet<>();
        for (TeamMember member : required) {
            if (!member.scope().organizationId().equals(workItem.scope().organizationId())
                    || !member.scope().teamId().equals(workItem.scope().teamId())) {
                throw new DomainValidationException(
                        "reviewerEligibilityPolicy.teamMembers",
                        "must contain only memberships from the WorkItem Team");
            }
            if (!ids.add(member.id()) || !principals.add(member.userPrincipalId())) {
                throw new DomainValidationException(
                        "reviewerEligibilityPolicy.teamMembers",
                        "must not contain duplicate memberships or USER Principals");
            }
        }
        return required;
    }

    private static Set<ResponsibilityRole> findConflicts(
            Collection<ResponsibilityAssignment> assignments,
            WorkItem workItem,
            Principal reviewer,
            TeamMember reviewerMember) {
        Collection<ResponsibilityAssignment> required =
                Objects.requireNonNull(assignments, "assignments");
        EnumSet<ResponsibilityRole> conflicts = EnumSet.noneOf(ResponsibilityRole.class);
        for (ResponsibilityAssignment assignment : required) {
            if (!assignment.workItemId().equals(workItem.id())
                    || !assignment.scope().equals(workItem.scope())) {
                throw new DomainValidationException(
                        "reviewerEligibilityPolicy.assignments",
                        "must contain only responsibilities from the expected WorkItem scope");
            }
            if (assignment.isActive()
                    && (assignment.role() == ResponsibilityRole.OWNER
                            || assignment.role() == ResponsibilityRole.EXECUTOR)
                    && (assignment.actorPrincipalId().equals(reviewer.id())
                            || assignment
                                    .actorMemberId()
                                    .filter(reviewerMember.id()::equals)
                                    .isPresent())) {
                conflicts.add(assignment.role());
            }
        }
        return conflicts.isEmpty() ? Set.of() : Set.copyOf(conflicts);
    }

    private static Optional<String> normalizeOverrideReason(Optional<String> reason) {
        Optional<String> required = Objects.requireNonNull(reason, "singleMemberOverrideReason");
        if (required.isEmpty()) {
            return required;
        }
        String normalized = required.orElseThrow().strip();
        if (normalized.isEmpty()) {
            throw new DomainValidationException(
                    "reviewerEligibilityPolicy.singleMemberOverrideReason",
                    "must not be blank");
        }
        if (normalized.length() > ReviewerEligibilityDecision.MAX_OVERRIDE_REASON_LENGTH) {
            throw new DomainValidationException(
                    "reviewerEligibilityPolicy.singleMemberOverrideReason",
                    "must contain at most "
                            + ReviewerEligibilityDecision.MAX_OVERRIDE_REASON_LENGTH
                            + " characters");
        }
        return Optional.of(normalized);
    }
}
