package io.crewscope.domain.conversation;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;

/** Shared Principal and TeamMember guard; application services still decide permissions. */
final class ConversationActorPolicy {

    private ConversationActorPolicy() {}

    static PrincipalId requireActiveOwner(
            TeamMember member, Principal user, ConversationScope scope, String field) {
        TeamMember requiredMember = Objects.requireNonNull(member, "member");
        Principal requiredUser = Objects.requireNonNull(user, "user");
        boolean userOutsideTeam = requiredUser.scope().teamId().isPresent()
                && requiredUser.scope().teamId().filter(scope.teamId()::equals).isEmpty();
        if (!requiredMember.canParticipate()
                || requiredUser.type() != PrincipalType.USER
                || !requiredUser.canAct()
                || !requiredMember.userPrincipalId().equals(requiredUser.id())
                || !requiredMember.scope().organizationId().equals(scope.organizationId())
                || !requiredMember.scope().teamId().equals(scope.teamId())
                || !requiredUser.scope().organizationId().equals(scope.organizationId())
                || userOutsideTeam) {
            throw new DomainValidationException(
                    field, "must reference an active USER member in the Conversation Team");
        }
        return requiredUser.id();
    }

    static PrincipalId requireActiveInScope(
            Principal principal, ConversationScope scope, String field) {
        Principal requiredPrincipal = Objects.requireNonNull(principal, "principal");
        boolean outsideTeam = requiredPrincipal.scope().teamId().isPresent()
                && requiredPrincipal.scope().teamId().filter(scope.teamId()::equals).isEmpty();
        if (!requiredPrincipal.canAct()
                || !requiredPrincipal.scope().organizationId().equals(scope.organizationId())
                || outsideTeam) {
            throw new DomainValidationException(
                    field,
                    "must reference an active Principal in the Conversation Organization and Team");
        }
        return requiredPrincipal.id();
    }
}
