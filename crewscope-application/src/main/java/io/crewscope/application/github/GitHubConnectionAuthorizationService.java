package io.crewscope.application.github;

import io.crewscope.application.credential.CredentialSubject;
import io.crewscope.application.credential.CredentialSubjectType;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Who is allowed to act on GitHub Connections: organization identity, connection
 * ownership and Team management gates. Extracted from
 * {@link GitHubConnectionApplicationService} in M11-E01; method bodies are
 * unchanged.
 */
public final class GitHubConnectionAuthorizationService {

    private final ConnectionRepository connections;
    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final TeamRoleRepository roles;
    private final MemberRoleRepository memberRoles;

    public GitHubConnectionAuthorizationService(
            ConnectionRepository connections,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository memberRoles) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.memberRoles = Objects.requireNonNull(memberRoles, "memberRoles");
    }

    GitHubConnectionOwnerFacts resolveCreateOwner(
            TeamAccessContext context, CreateGitHubConnectionRequest request, UtcTimestamp now) {
        Principal actor = context.actor();
        return switch (request.authenticationType()) {
            case APP_INSTALLATION -> {
                TeamId teamId = request.teamId().orElseThrow(() -> new DomainValidationException(
                        "githubConnection.teamId", "is required for APP_INSTALLATION"));
                Team team = requireTeam(actor.scope().organizationId(), teamId);
                requireTeamManage(context, team, now);
                CredentialSubject subject = switch (request.credentialSubjectType()) {
                    case TEAM -> CredentialSubject.team(team.organizationId(), team.id());
                    case ORGANIZATION -> {
                        if (!context.platformAdministrator()) {
                            throw new PolicyDeniedException(
                                    "use an Organization credential for a Team GitHub Connection");
                        }
                        yield CredentialSubject.organization(team.organizationId());
                    }
                    case PRINCIPAL -> throw new DomainValidationException(
                            "githubConnection.credentialSubjectType",
                            "must be TEAM or ORGANIZATION for APP_INSTALLATION");
                };
                yield new GitHubConnectionOwnerFacts(ProviderOwner.team(team), subject);
            }
            case OAUTH_USER -> {
                if (request.teamId().isPresent()
                        || request.credentialSubjectType() != CredentialSubjectType.PRINCIPAL) {
                    throw new DomainValidationException(
                            "githubConnection.owner",
                            "OAUTH_USER requires USER ownership and a PRINCIPAL credential");
                }
                yield new GitHubConnectionOwnerFacts(
                        ProviderOwner.user(actor),
                        CredentialSubject.principal(actor.scope().organizationId(), actor.id()));
            }
        };
    }

    ProviderOwner resolveListOwner(
            TeamAccessContext context,
            OrganizationId organizationId,
            ProviderOwnerType ownerType,
            Optional<TeamId> teamId) {
        Principal actor = requireOrganizationUser(context, organizationId);
        return switch (Objects.requireNonNull(ownerType, "ownerType")) {
            case USER -> {
                if (teamId.isPresent()) {
                    throw new DomainValidationException("githubConnection.teamId", "must be absent for USER");
                }
                yield ProviderOwner.user(actor);
            }
            case TEAM -> {
                Team team = requireTeam(organizationId, teamId.orElseThrow(() ->
                        new DomainValidationException("githubConnection.teamId", "is required for TEAM")));
                requireActiveMember(actor, team);
                yield ProviderOwner.team(team);
            }
            case ORGANIZATION -> throw new DomainValidationException(
                    "githubConnection.ownerType", "ORGANIZATION GitHub Connections are not supported");
        };
    }

    Connection requireVisibleConnection(
            TeamAccessContext context, OrganizationId organizationId, ConnectionId connectionId) {
        requireOrganizationUser(context, organizationId);
        Connection connection = requireConnection(organizationId, connectionId);
        requireViewOwner(context, connection.owner());
        return connection;
    }

    Connection requireConnection(OrganizationId organizationId, ConnectionId connectionId) {
        return connections.findById(organizationId, Objects.requireNonNull(connectionId, "connectionId"))
                .filter(value -> GitHubConnectionApplicationService.CONNECTOR_KEY.equals(value.connectorKey()))
                .orElseThrow(() -> new AggregateNotFoundException("Connection", connectionId));
    }

    void requireManageOwner(TeamAccessContext context, ProviderOwner owner, UtcTimestamp now) {
        switch (owner.type()) {
            case USER -> {
                if (!owner.userPrincipalId().orElseThrow().equals(context.actor().id())) {
                    throw new PolicyDeniedException("manage this GitHub Connection");
                }
            }
            case TEAM -> requireTeamManage(
                    context, requireTeam(owner.organizationId(), owner.teamId().orElseThrow()), now);
            case ORGANIZATION -> throw new PolicyDeniedException("manage this GitHub Connection");
        }
    }

    void requireBindingAuthority(
            TeamAccessContext context, ProviderOwner owner, TeamId targetTeamId, UtcTimestamp now) {
        Team targetTeam = requireTeam(owner.organizationId(), targetTeamId);
        if (owner.type() == ProviderOwnerType.TEAM) {
            if (owner.teamId().filter(targetTeamId::equals).isEmpty()) {
                throw new PolicyDeniedException("bind a Team GitHub Connection across Teams");
            }
            requireTeamManage(context, targetTeam, now);
            return;
        }
        if (owner.type() == ProviderOwnerType.USER
                && owner.userPrincipalId().filter(context.actor().id()::equals).isPresent()) {
            requireActiveMember(context.actor(), targetTeam);
            return;
        }
        throw new PolicyDeniedException("bind this GitHub Connection");
    }

    Team requireTeam(OrganizationId organizationId, TeamId teamId) {
        if (teams.findUninitializedById(organizationId, teamId).isPresent()) {
            throw new DomainValidationException("team.initializationStatus", "must be READY");
        }
        return teams.findById(organizationId, teamId)
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
    }

    static Principal requireOrganizationUser(
            TeamAccessContext context, OrganizationId organizationId) {
        TeamAccessContext trusted = Objects.requireNonNull(context, "context");
        Principal actor = trusted.actor();
        if (actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException("act in this Organization");
        }
        return actor;
    }

    static ProviderExecutionIdentity executionIdentity(ProviderOwnerType ownerType) {
        return switch (ownerType) {
            case USER -> ProviderExecutionIdentity.DELEGATED_USER;
            case TEAM -> ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT;
            case ORGANIZATION -> ProviderExecutionIdentity.ORGANIZATION_SERVICE_ACCOUNT;
        };
    }

    static GitHubAuthenticationType authenticationType(ProviderOwnerType ownerType) {
        return switch (ownerType) {
            case TEAM -> GitHubAuthenticationType.APP_INSTALLATION;
            case USER -> GitHubAuthenticationType.OAUTH_USER;
            case ORGANIZATION -> throw new DomainValidationException(
                    "githubConnection.ownerType", "ORGANIZATION ownership is not supported");
        };
    }

    private void requireViewOwner(TeamAccessContext context, ProviderOwner owner) {
        switch (owner.type()) {
            case USER -> {
                if (!owner.userPrincipalId().orElseThrow().equals(context.actor().id())) {
                    throw new PolicyDeniedException("view this GitHub Connection");
                }
            }
            case TEAM -> requireActiveMember(
                    context.actor(), requireTeam(owner.organizationId(), owner.teamId().orElseThrow()));
            case ORGANIZATION -> throw new PolicyDeniedException("view this GitHub Connection");
        }
    }

    private void requireTeamManage(TeamAccessContext context, Team team, UtcTimestamp now) {
        if (context.platformAdministrator()) {
            return;
        }
        TeamMember member = requireActiveMember(context.actor(), team);
        Map<TeamRoleId, TeamRole> rolesById = roles.findByTeam(team.organizationId(), team.id()).stream()
                .collect(Collectors.toMap(TeamRole::id, value -> value));
        boolean allowed = memberRoles.findByMember(team.organizationId(), member.id()).stream()
                .filter(value -> value.status() == MemberRoleStatus.ACTIVE)
                .filter(value -> value.isEffectiveAt(now))
                .filter(value -> value.roleScope().equals(RoleScope.team()))
                .map(value -> rolesById.get(value.teamRoleId()))
                .filter(Objects::nonNull)
                .filter(TeamRole::isGrantable)
                .anyMatch(value -> value.permissions().contains(TeamPermission.PROVIDER_MANAGE));
        if (!allowed) {
            throw new PolicyDeniedException("manage Team GitHub Connections");
        }
    }

    private TeamMember requireActiveMember(Principal actor, Team team) {
        return memberships.findByTeam(team.organizationId(), team.id()).stream()
                .filter(value -> value.userPrincipalId().equals(actor.id()))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException("access this Team's GitHub Connections"));
    }
}
