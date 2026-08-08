package io.crewscope.domain.responsibility;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Reports that a human Reviewer cannot receive Gate authority under the effective PolicyPack. */
public final class ReviewerPolicyViolationException extends DomainException {

    public ReviewerPolicyViolationException(
            WorkItemId workItemId,
            TeamMemberId reviewerMemberId,
            Set<ResponsibilityRole> conflictingRoles,
            int activeMemberCount,
            boolean singleMemberOverrideConfigured) {
        super(error(
                workItemId,
                reviewerMemberId,
                conflictingRoles,
                activeMemberCount,
                singleMemberOverrideConfigured));
    }

    private static DomainError error(
            WorkItemId workItemId,
            TeamMemberId reviewerMemberId,
            Set<ResponsibilityRole> conflictingRoles,
            int activeMemberCount,
            boolean singleMemberOverrideConfigured) {
        WorkItemId requiredWorkItemId = Objects.requireNonNull(workItemId, "workItemId");
        TeamMemberId requiredMemberId =
                Objects.requireNonNull(reviewerMemberId, "reviewerMemberId");
        Set<ResponsibilityRole> requiredRoles =
                Set.copyOf(Objects.requireNonNull(conflictingRoles, "conflictingRoles"));
        if (requiredRoles.isEmpty()) {
            throw new IllegalArgumentException("conflictingRoles must not be empty");
        }
        if (requiredRoles.stream().anyMatch(role ->
                role != ResponsibilityRole.OWNER && role != ResponsibilityRole.EXECUTOR)) {
            throw new IllegalArgumentException(
                    "conflictingRoles can contain only OWNER and EXECUTOR");
        }
        if (activeMemberCount < 0) {
            throw new IllegalArgumentException("activeMemberCount must not be negative");
        }
        String roles = requiredRoles.stream()
                .sorted()
                .map(Enum::name)
                .collect(Collectors.joining(","));
        return new DomainError(
                DomainErrorCode.POLICY_DENIED,
                "Gate Reviewer violates Owner/Executor duty separation",
                Map.of(
                        "workItemId", requiredWorkItemId.toString(),
                        "reviewerMemberId", requiredMemberId.toString(),
                        "conflictingRoles", roles,
                        "activeMemberCount", Integer.toString(activeMemberCount),
                        "singleMemberOverrideConfigured",
                        Boolean.toString(singleMemberOverrideConfigured)));
    }
}
