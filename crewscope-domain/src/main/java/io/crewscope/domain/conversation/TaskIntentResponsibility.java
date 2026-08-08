package io.crewscope.domain.conversation;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Persistable responsibility proposal captured before a WorkItem exists. */
public record TaskIntentResponsibility(
        ResponsibilityRole role,
        PrincipalId principalId,
        PrincipalType principalType,
        Optional<TeamMemberId> memberId) {

    public TaskIntentResponsibility {
        role = Objects.requireNonNull(role, "role");
        principalId = Objects.requireNonNull(principalId, "principalId");
        principalType = Objects.requireNonNull(principalType, "principalType");
        memberId = requireShape(role, principalType, memberId);
    }

    static TaskIntentResponsibility resolve(
            ResponsibilityRole role, TaskIntentCandidate candidate, WorkItemScope targetScope) {
        TaskIntentCandidate requiredCandidate = Objects.requireNonNull(candidate, "candidate");
        Principal principal = requiredCandidate.principal();
        WorkItemScope requiredScope = Objects.requireNonNull(targetScope, "targetScope");
        requirePrincipalInScope(principal, requiredScope);
        Optional<TeamMemberId> memberId = requireCurrentMember(
                principal, requiredCandidate.member(), requiredScope);
        return new TaskIntentResponsibility(role, principal.id(), principal.type(), memberId);
    }

    private static Optional<TeamMemberId> requireCurrentMember(
            Principal principal, Optional<TeamMember> member, WorkItemScope scope) {
        if (principal.type() == PrincipalType.USER) {
            TeamMember requiredMember = member.orElseThrow(() -> new DomainValidationException(
                    "taskIntent.responsibility.memberId",
                    "is required for a USER responsibility candidate"));
            if (!requiredMember.canParticipate()
                    || !requiredMember.userPrincipalId().equals(principal.id())
                    || !requiredMember.scope().organizationId().equals(scope.organizationId())
                    || !requiredMember.scope().teamId().equals(scope.teamId())) {
                throw new DomainValidationException(
                        "taskIntent.responsibility.memberId",
                        "must reference the Principal's active membership in the target Team");
            }
            return Optional.of(requiredMember.id());
        }
        if (member.isPresent()) {
            throw new DomainValidationException(
                    "taskIntent.responsibility.memberId",
                    "is only allowed for a USER responsibility candidate");
        }
        if (principal.scope().teamId().filter(scope.teamId()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "taskIntent.responsibility.principalId",
                    "an Agent responsibility candidate must belong to the target Team");
        }
        return Optional.empty();
    }

    private static void requirePrincipalInScope(Principal principal, WorkItemScope scope) {
        boolean outsideTeam = principal.scope().teamId().isPresent()
                && principal.scope().teamId().filter(scope.teamId()::equals).isEmpty();
        if (!principal.canAct()
                || !principal.scope().organizationId().equals(scope.organizationId())
                || outsideTeam) {
            throw new DomainValidationException(
                    "taskIntent.responsibility.principalId",
                    "must reference an active Principal in the target Organization and Team");
        }
    }

    private static Optional<TeamMemberId> requireShape(
            ResponsibilityRole role,
            PrincipalType principalType,
            Optional<TeamMemberId> memberId) {
        Optional<TeamMemberId> requiredMember = Objects.requireNonNull(memberId, "memberId");
        boolean allowedType = switch (role) {
            case OWNER, REVIEWER -> principalType == PrincipalType.USER;
            case EXECUTOR -> principalType == PrincipalType.USER || principalType.isAgent();
        };
        if (!allowedType) {
            throw new DomainValidationException(
                    "taskIntent.responsibility.principalId",
                    "Principal type " + principalType + " cannot hold role " + role);
        }
        if ((principalType == PrincipalType.USER) != requiredMember.isPresent()) {
            throw new DomainValidationException(
                    "taskIntent.responsibility.memberId",
                    principalType == PrincipalType.USER
                            ? "is required for a USER responsibility candidate"
                            : "must be empty for an Agent responsibility candidate");
        }
        return requiredMember;
    }
}
