package io.crewscope.application.audit;

import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
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
import java.util.Set;
import java.util.stream.Collectors;

/** Re-evaluates current USER, Membership, Role and Grant facts for every Audit request. */
public final class DefaultAuditAuthorization implements AuditAuthorization {

    private final TeamMembershipQuery memberships;
    private final TeamRoleRepository roles;
    private final MemberRoleRepository grants;

    public DefaultAuditAuthorization(
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants) {
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.grants = Objects.requireNonNull(grants, "grants");
    }

    @Override
    public void requireRead(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            UtcTimestamp occurredAt) {
        requirePermissions(
                context,
                organizationId,
                teamId,
                occurredAt,
                Set.of(TeamPermission.AUDIT_READ),
                "read Team Audit events");
    }

    @Override
    public void requireExport(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            UtcTimestamp occurredAt) {
        requirePermissions(
                context,
                organizationId,
                teamId,
                occurredAt,
                Set.of(TeamPermission.AUDIT_READ, TeamPermission.GOVERNANCE_EXPORT),
                "export Team Audit events");
    }

    private void requirePermissions(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            UtcTimestamp occurredAt,
            Set<TeamPermission> requiredPermissions,
            String action) {
        TeamAccessContext trusted = Objects.requireNonNull(context, "context");
        Principal actor = trusted.actor();
        if (actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException(action);
        }
        if (trusted.platformAdministrator()) {
            return;
        }
        TeamMember member = memberships.findByTeam(organizationId, teamId).stream()
                .filter(value -> value.userPrincipalId().equals(actor.id()))
                .filter(value -> value.scope().organizationId().equals(organizationId))
                .filter(value -> value.scope().teamId().equals(teamId))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException(action));
        Map<TeamRoleId, TeamRole> roleIndex = roles.findByTeam(organizationId, teamId).stream()
                .collect(Collectors.toMap(TeamRole::id, role -> role));
        Set<TeamPermission> permissions = grants.findByMember(organizationId, member.id()).stream()
                .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
                .filter(grant -> grant.isEffectiveAt(occurredAt))
                .filter(grant -> grant.roleScope().equals(RoleScope.team()))
                .map(grant -> roleIndex.get(grant.teamRoleId()))
                .filter(Objects::nonNull)
                .filter(TeamRole::isGrantable)
                .flatMap(role -> role.permissions().stream())
                .collect(Collectors.toSet());
        if (!permissions.containsAll(requiredPermissions)) {
            throw new PolicyDeniedException(action);
        }
    }
}
