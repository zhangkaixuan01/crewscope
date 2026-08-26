package io.crewscope.application.collaboration;

import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Evaluates current ACTIVE Team membership and PROVIDER_MANAGE role grants. */
public final class DefaultLarkMappingAdministration implements LarkMappingAdministration {

    private final TeamMemberRepository members;
    private final TeamRoleRepository roles;
    private final MemberRoleRepository grants;

    public DefaultLarkMappingAdministration(
            TeamMemberRepository members,
            TeamRoleRepository roles,
            MemberRoleRepository grants) {
        this.members = Objects.requireNonNull(members, "members");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.grants = Objects.requireNonNull(grants, "grants");
    }

    @Override
    public void requireProviderAdministrator(
            OrganizationId organizationId,
            TeamId teamId,
            Principal actor,
            UtcTimestamp occurredAt) {
        OrganizationId organization = Objects.requireNonNull(
                organizationId, "organizationId");
        TeamId team = Objects.requireNonNull(teamId, "teamId");
        Principal principal = Objects.requireNonNull(actor, "actor");
        UtcTimestamp now = Objects.requireNonNull(occurredAt, "occurredAt");
        if (!principal.canAct()
                || !principal.scope().organizationId().equals(organization)
                || principal.scope().teamId().filter(team::equals).isEmpty()) {
            throw denied();
        }
        TeamMember member = members.findByTeamAndUserPrincipalId(
                        organization, team, principal.id())
                .filter(TeamMember::canParticipate)
                .orElseThrow(DefaultLarkMappingAdministration::denied);
        Map<TeamRoleId, TeamRole> rolesById = roles.findByTeam(organization, team).stream()
                .collect(Collectors.toMap(TeamRole::id, Function.identity()));
        boolean allowed = grants.findByMember(organization, member.id()).stream()
                .filter(value -> value.status() == MemberRoleStatus.ACTIVE)
                .filter(value -> value.isEffectiveAt(now))
                .filter(value -> value.roleScope().equals(RoleScope.team()))
                .map(value -> rolesById.get(value.teamRoleId()))
                .filter(Objects::nonNull)
                .filter(TeamRole::isGrantable)
                .anyMatch(value -> value.permissions().contains(TeamPermission.PROVIDER_MANAGE));
        if (!allowed) {
            throw denied();
        }
    }

    private static PolicyDeniedException denied() {
        return new PolicyDeniedException("manage Team Lark mappings");
    }
}
