package io.crewscope.application.team;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;

/**
 * Fail-closed membership revalidation for in-flight execution channels (ADR-038 §2).
 *
 * <p>Every check reloads the current {@code TeamMember} fact directly; nothing is cached, and a
 * repository failure surfaces as an exception so the caller stops instead of proceeding on stale
 * authorization. Side-effect boundaries of an already-started run (Personal Agent segment
 * commits, Task Worker heartbeats and event commits) call this guard between the read of a
 * business fact and its first outward effect, so a revoked member loses the remainder of that
 * run instead of completing it.
 */
public final class MemberAuthorizationGuard {

    private final TeamMemberRepository members;
    private final PrincipalRepository principals;

    public MemberAuthorizationGuard(
            TeamMemberRepository members, PrincipalRepository principals) {
        this.members = Objects.requireNonNull(members, "members");
        this.principals = Objects.requireNonNull(principals, "principals");
    }

    /** Requires one currently participating membership for the given user principal. */
    public void requireParticipation(
            OrganizationId organizationId, TeamId teamId, PrincipalId userPrincipalId) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        TeamId team = Objects.requireNonNull(teamId, "teamId");
        PrincipalId user = Objects.requireNonNull(userPrincipalId, "userPrincipalId");
        members.findByTeamAndUserPrincipalId(organization, team, user)
                .filter(candidate -> candidate.scope().organizationId().equals(organization))
                .filter(candidate -> candidate.scope().teamId().equals(team))
                .filter(TeamMember::canParticipate)
                .orElseThrow(() -> denied());
    }

    /** Requires the owning user of an Agent principal to still participate in the Team. */
    public void requireAgentOwnerParticipation(
            OrganizationId organizationId, TeamId teamId, PrincipalId agentPrincipalId) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        TeamId team = Objects.requireNonNull(teamId, "teamId");
        PrincipalId agentId = Objects.requireNonNull(agentPrincipalId, "agentPrincipalId");
        Principal agent = principals.findById(organization, agentId)
                .orElseThrow(MemberAuthorizationGuard::denied);
        if (!agent.canAct() || !agent.type().isAgent()) {
            throw denied();
        }
        PrincipalId ownerId = agent.ownerPrincipalId().orElseThrow(MemberAuthorizationGuard::denied);
        requireParticipation(organization, team, ownerId);
    }

    private static PolicyDeniedException denied() {
        return new PolicyDeniedException(
                "keep executing with this Team membership");
    }
}
