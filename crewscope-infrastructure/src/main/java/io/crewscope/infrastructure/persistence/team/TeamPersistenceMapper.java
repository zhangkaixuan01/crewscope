package io.crewscope.infrastructure.persistence.team;

import static io.crewscope.infrastructure.persistence.PersistenceMappingSupport.audit;
import static io.crewscope.infrastructure.persistence.PersistenceMappingSupport.lifecycle;

import io.crewscope.domain.identity.ExternalIdentity;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.RoleScopeType;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.TeamRoleKey;
import io.crewscope.domain.team.TeamRoleStatus;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.team.TeamStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.AgentProfileType;
import io.crewscope.domain.workspace.Workspace;
import io.crewscope.domain.workspace.WorkspaceScope;
import io.crewscope.domain.workspace.WorkspaceStatus;
import io.crewscope.domain.workspace.WorkspaceType;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Maps Team collaboration entities without introducing implicit ORM relationships. */
@Component
public final class TeamPersistenceMapper {

    public TeamEntity toEntity(Team value) {
        return new TeamEntity(
                value.id().value(),
                value.organizationId().value(),
                value.name(),
                value.ownerMemberId().value(),
                value.defaultWorkspaceId().value(),
                value.status().name(),
                value.version(),
                value.audit().createdAt().value(),
                value.audit().createdBy().map(PrincipalId::value).orElse(null),
                value.audit().updatedAt().value(),
                value.audit().updatedBy().map(PrincipalId::value).orElse(null));
    }

    public Team toDomain(TeamEntity value) {
        if (value.ownerMemberId() == null || value.defaultWorkspaceId() == null) {
            throw new TeamInitializationRequiredException(value.id());
        }
        return Team.reconstitute(
                new TeamId(value.id()),
                new OrganizationId(value.organizationId()),
                value.name(),
                new TeamMemberId(value.ownerMemberId()),
                new WorkspaceId(value.defaultWorkspaceId()),
                TeamStatus.valueOf(value.status()),
                value.version(),
                audit(value.createdBy(), value.createdAt(), value.updatedBy(), value.updatedAt()));
    }

    public WorkspaceEntity toEntity(Workspace value) {
        return new WorkspaceEntity(
                value.id().value(),
                value.scope().organizationId().value(),
                value.scope().teamId().map(TeamId::value).orElse(null),
                value.type().name(),
                value.ownerPrincipalId().map(PrincipalId::value).orElse(null),
                value.name(),
                value.status().name(),
                value.version(),
                value.audit().createdAt().value(),
                value.audit().createdBy().map(PrincipalId::value).orElse(null),
                value.audit().updatedAt().value(),
                value.audit().updatedBy().map(PrincipalId::value).orElse(null));
    }

    public Workspace toDomain(WorkspaceEntity value) {
        return Workspace.reconstitute(
                new WorkspaceId(value.id()),
                new WorkspaceScope(
                        new OrganizationId(value.organizationId()),
                        Optional.ofNullable(value.teamId()).map(TeamId::new)),
                WorkspaceType.valueOf(value.type()),
                Optional.ofNullable(value.ownerPrincipalId()).map(PrincipalId::new),
                value.name(),
                WorkspaceStatus.valueOf(value.status()),
                value.version(),
                audit(value.createdBy(), value.createdAt(), value.updatedBy(), value.updatedAt()));
    }

    public TeamMemberEntity toEntity(TeamMember value) {
        return new TeamMemberEntity(
                value.id().value(),
                value.scope().organizationId().value(),
                value.scope().teamId().value(),
                value.userPrincipalId().value(),
                value.status().name(),
                value.joinMethod().name(),
                value.invitedByPrincipalId().map(PrincipalId::value).orElse(null),
                value.joinedAt().map(timestamp -> timestamp.value()).orElse(null),
                value.lastActiveAt().map(timestamp -> timestamp.value()).orElse(null),
                value.version(),
                value.lifecycle().createdAt().value(),
                value.lifecycle().updatedAt().value());
    }

    public TeamMember toDomain(TeamMemberEntity value) {
        return TeamMember.reconstitute(
                new TeamMemberId(value.id()),
                new TeamScope(
                        new OrganizationId(value.organizationId()), new TeamId(value.teamId())),
                new PrincipalId(value.userPrincipalId()),
                TeamMemberStatus.valueOf(value.status()),
                TeamJoinMethod.valueOf(value.joinMethod()),
                Optional.ofNullable(value.invitedBy()).map(PrincipalId::new),
                Optional.ofNullable(value.joinedAt())
                        .map(io.crewscope.domain.shared.time.UtcTimestamp::from),
                Optional.ofNullable(value.lastActiveAt())
                        .map(io.crewscope.domain.shared.time.UtcTimestamp::from),
                value.version(),
                lifecycle(value.createdAt(), value.updatedAt()));
    }

    public TeamRoleEntity toEntity(TeamRole value) {
        return new TeamRoleEntity(
                value.id().value(),
                value.scope().organizationId().value(),
                value.scope().teamId().value(),
                value.key().value(),
                value.name(),
                value.description().orElse(null),
                value.builtIn(),
                value.permissions().stream().map(Enum::name).sorted().toList(),
                value.scopeType().name(),
                value.status().name(),
                value.version(),
                value.lifecycle().createdAt().value(),
                value.lifecycle().updatedAt().value());
    }

    public TeamRole toDomain(TeamRoleEntity value) {
        Set<TeamPermission> permissions =
                value.permissions().stream()
                        .map(TeamPermission::valueOf)
                        .collect(Collectors.toUnmodifiableSet());
        return TeamRole.reconstitute(
                new TeamRoleId(value.id()),
                new TeamScope(
                        new OrganizationId(value.organizationId()), new TeamId(value.teamId())),
                new TeamRoleKey(value.roleKey()),
                value.name(),
                Optional.ofNullable(value.description()),
                value.builtIn(),
                permissions,
                RoleScopeType.valueOf(value.scopeType()),
                TeamRoleStatus.valueOf(value.status()),
                value.version(),
                lifecycle(value.createdAt(), value.updatedAt()));
    }

    public MemberRoleEntity toEntity(MemberRole value) {
        return new MemberRoleEntity(
                value.id().value(),
                value.teamScope().organizationId().value(),
                value.teamScope().teamId().value(),
                value.teamMemberId().value(),
                value.teamRoleId().value(),
                value.roleScope().type().name(),
                value.roleScope().workProjectId().map(WorkProjectId::value).orElse(null),
                value.grantedByPrincipalId().value(),
                value.grantedAt().value(),
                value.validFrom().value(),
                value.expiresAt().map(t -> t.value()).orElse(null),
                value.revokedAt().map(t -> t.value()).orElse(null),
                value.status().name(),
                value.version(),
                value.lifecycle().createdAt().value(),
                value.lifecycle().updatedAt().value());
    }

    public MemberRole toDomain(MemberRoleEntity value) {
        RoleScope roleScope =
                RoleScopeType.valueOf(value.scopeType()) == RoleScopeType.TEAM
                        ? RoleScope.team()
                        : RoleScope.workProject(new WorkProjectId(value.scopeId()));
        return MemberRole.reconstitute(
                new MemberRoleId(value.id()),
                new TeamScope(
                        new OrganizationId(value.organizationId()), new TeamId(value.teamId())),
                new TeamMemberId(value.teamMemberId()),
                new TeamRoleId(value.teamRoleId()),
                roleScope,
                new PrincipalId(value.grantedBy()),
                io.crewscope.domain.shared.time.UtcTimestamp.from(value.grantedAt()),
                io.crewscope.domain.shared.time.UtcTimestamp.from(value.validFrom()),
                Optional.ofNullable(value.expiresAt())
                        .map(io.crewscope.domain.shared.time.UtcTimestamp::from),
                Optional.ofNullable(value.revokedAt())
                        .map(io.crewscope.domain.shared.time.UtcTimestamp::from),
                MemberRoleStatus.valueOf(value.status()),
                value.version(),
                lifecycle(value.createdAt(), value.updatedAt()));
    }

    public PrincipalEntity toEntity(Principal value) {
        Optional<ExternalIdentity> external = value.externalIdentity();
        return new PrincipalEntity(
                value.id().value(),
                value.scope().organizationId().value(),
                value.scope().teamId().map(TeamId::value).orElse(null),
                value.type().name(),
                value.ownerPrincipalId().map(PrincipalId::value).orElse(null),
                value.displayName(),
                external.map(ExternalIdentity::provider).orElse(null),
                external.map(ExternalIdentity::subject).orElse(null),
                value.visibility().name(),
                value.status().name(),
                value.version(),
                value.lifecycle().createdAt().value(),
                value.lifecycle().updatedAt().value());
    }

    public Principal toDomain(PrincipalEntity value) {
        Optional<ExternalIdentity> external =
                value.identityProvider() == null
                        ? Optional.empty()
                        : Optional.of(
                                new ExternalIdentity(
                                        value.identityProvider(), value.externalSubject()));
        return Principal.reconstitute(
                new PrincipalId(value.id()),
                new PrincipalScope(
                        new OrganizationId(value.organizationId()),
                        Optional.ofNullable(value.teamId()).map(TeamId::new)),
                PrincipalType.valueOf(value.type()),
                Optional.ofNullable(value.ownerPrincipalId()).map(PrincipalId::new),
                value.displayName(),
                external,
                PrincipalVisibility.valueOf(value.visibility()),
                PrincipalStatus.valueOf(value.status()),
                value.version(),
                lifecycle(value.createdAt(), value.updatedAt()));
    }

    public AgentProfileEntity toEntity(AgentProfile value) {
        return new AgentProfileEntity(
                value.id().value(),
                value.scope().organizationId().value(),
                value.scope().teamId().orElseThrow().value(),
                value.workspaceId().value(),
                value.agentPrincipalId().value(),
                value.ownerMemberId().map(TeamMemberId::value).orElse(null),
                value.type().name(),
                value.defaultProfile(),
                value.status().name(),
                value.version(),
                value.audit().createdAt().value(),
                value.audit().createdBy().orElseThrow().value(),
                value.audit().updatedAt().value(),
                value.audit().updatedBy().orElseThrow().value());
    }

    public AgentProfile toDomain(AgentProfileEntity value) {
        return AgentProfile.reconstitute(
                new AgentProfileId(value.id()),
                WorkspaceScope.team(
                        new OrganizationId(value.organizationId()), new TeamId(value.teamId())),
                new WorkspaceId(value.workspaceId()),
                new PrincipalId(value.agentPrincipalId()),
                Optional.ofNullable(value.ownerMemberId()).map(TeamMemberId::new),
                AgentProfileType.valueOf(value.type()),
                value.defaultProfile(),
                AgentProfileStatus.valueOf(value.status()),
                value.version(),
                audit(value.createdBy(), value.createdAt(), value.updatedBy(), value.updatedAt()));
    }
}
