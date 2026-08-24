package io.crewscope.application.task;

import io.crewscope.application.agent.AgentExecutionConfigurationService;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.AgentModelGovernanceSnapshot;
import io.crewscope.application.agent.ResolveAgentExecutionConfigurationRequest;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.model.ModelConnectionAvailabilityVerifier;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentExecutionScopeFacts;
import io.crewscope.domain.agent.AgentExecutionScopePolicy;
import io.crewscope.domain.agent.AgentModelPreflightException;
import io.crewscope.domain.agent.AgentModelPreflightRejectionCode;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workitem.WorkItem;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves Task execution scope and the exact Agent model graph from durable server facts. */
public final class TaskAgentSelectionService {

    private final AgentProfileRepository profiles;
    private final PrincipalRepository principals;
    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final ModelConnectionRepository connections;
    private final ModelConnectionAvailabilityVerifier availability;
    private final AgentModelGovernance governance;
    private final AgentExecutionConfigurationService configurations;

    public TaskAgentSelectionService(
            AgentProfileRepository profiles,
            PrincipalRepository principals,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            ModelConnectionRepository connections,
            ModelConnectionAvailabilityVerifier availability,
            AgentModelGovernance governance,
            AgentExecutionConfigurationService configurations) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.availability = Objects.requireNonNull(availability, "availability");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
    }

    /** Resolves the current or explicitly selected revision after responsibility authorization. */
    public TaskAgentExecutionSelection resolve(
            TeamAccessContext context,
            WorkItem workItem,
            List<ResponsibilityAssignment> assignments,
            TaskAgentSelectionRequest selection,
            UtcTimestamp resolvedAt) {
        SelectionFacts facts = requireFacts(
                context, workItem, assignments, selection.agentProfileId(), resolvedAt);
        ResolveAgentExecutionConfigurationRequest request = resolutionRequest(
                facts, selection, resolvedAt);
        ResolvedAgentExecutionConfiguration resolved = configurations.resolve(request);
        requireResolvedCoordinates(facts, resolved);
        return new TaskAgentExecutionSelection(
                facts.profile(), facts.executor(), request, resolved);
    }

    /** Re-authorizes a pinned Schema-v2 graph without re-inheriting a changed Team default. */
    public TaskAgentExecutionSelection authorizePinned(
            TeamAccessContext context,
            WorkItem workItem,
            List<ResponsibilityAssignment> assignments,
            ResolvedAgentExecutionConfiguration pinned,
            UtcTimestamp checkedAt) {
        ResolvedAgentExecutionConfiguration required = Objects.requireNonNull(pinned, "pinned");
        TaskAgentSelectionRequest selection = new TaskAgentSelectionRequest(
                required.agentProfileId(), Optional.of(required.configurationRevision()));
        SelectionFacts facts = requireFacts(
                context, workItem, assignments, required.agentProfileId(), checkedAt);
        requireResolvedCoordinates(facts, required);
        requirePinnedConnection(facts, required.primary().connectionId(), checkedAt);
        required.fallback().ifPresent(value ->
                requirePinnedConnection(facts, value.connectionId(), checkedAt));
        return new TaskAgentExecutionSelection(
                facts.profile(),
                facts.executor(),
                resolutionRequest(facts, selection, checkedAt),
                required);
    }

    private SelectionFacts requireFacts(
            TeamAccessContext context,
            WorkItem workItem,
            List<ResponsibilityAssignment> assignments,
            io.crewscope.domain.workspace.AgentProfileId profileId,
            UtcTimestamp now) {
        WorkItem item = Objects.requireNonNull(workItem, "workItem");
        Principal actor = requireActor(context, item);
        Team team = teams.findById(item.scope().organizationId(), item.scope().teamId())
                .filter(Team::isActive)
                .orElseThrow(() -> new AggregateNotFoundException("Team", item.scope().teamId()));
        TeamMember actorMember = requireActiveMember(team, actor.id());
        AgentProfile profile = profiles.findById(item.scope().organizationId(), profileId)
                .filter(value -> value.status() == AgentProfileStatus.ACTIVE)
                .filter(value -> value.scope().teamId().filter(item.scope().teamId()::equals).isPresent())
                .filter(value -> value.workspaceId().equals(item.scope().workspaceId()))
                .orElseThrow(() -> new AgentModelPreflightException(
                        AgentModelPreflightRejectionCode.AGENT_UNAVAILABLE));
        Principal executor = principals.findById(
                        item.scope().organizationId(), profile.agentPrincipalId())
                .filter(Principal::canAct)
                .filter(value -> value.type().isAgent())
                .orElseThrow(() -> new AgentModelPreflightException(
                        AgentModelPreflightRejectionCode.PRINCIPAL_INACTIVE));
        List<ResponsibilityAssignment> currentAssignments = List.copyOf(
                Objects.requireNonNull(assignments, "assignments"));
        long executorAssignments = currentAssignments.stream()
                .filter(ResponsibilityAssignment::isActive)
                .filter(value -> value.role() == ResponsibilityRole.EXECUTOR)
                .filter(value -> value.scope().equals(item.scope()))
                .filter(value -> value.actorPrincipalId().equals(executor.id()))
                .count();
        if (executorAssignments != 1) {
            throw new AgentModelPreflightException(
                    AgentModelPreflightRejectionCode.RESPONSIBILITY_REQUIRED);
        }

        Optional<TeamMember> ownerMember = profile.ownership().ownerMemberId()
                .map(ownerId -> memberships.findByTeam(
                                item.scope().organizationId(), item.scope().teamId())
                        .stream()
                        .filter(value -> value.id().equals(ownerId))
                        .filter(TeamMember::canParticipate)
                        .findFirst()
                        .orElseThrow(() -> new AgentModelPreflightException(
                                AgentModelPreflightRejectionCode.TEAM_PARTICIPATION_REQUIRED)));
        Optional<Principal> ownerUser = ownerMember.map(value -> principals.findById(
                        item.scope().organizationId(), value.userPrincipalId())
                .filter(Principal::canAct)
                .filter(candidate -> candidate.type() == PrincipalType.USER)
                .orElseThrow(() -> new AgentModelPreflightException(
                        AgentModelPreflightRejectionCode.PRINCIPAL_INACTIVE)));
        if (ownerUser.isPresent()
                && executor.ownerPrincipalId().filter(ownerUser.orElseThrow().id()::equals).isEmpty()) {
            throw new AgentModelPreflightException(
                    AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }

        AgentExecutionScopeFacts scopeFacts = scopeFacts(
                actor, actorMember, profile, executor, ownerMember, currentAssignments);
        AgentExecutionScope executionScope = AgentExecutionScopePolicy.resolve(scopeFacts);
        List<ModelConnection> usableConnections = usableConnections(
                team, profile, ownerUser, executionScope);
        AgentModelGovernanceSnapshot policy = governance.resolve(
                actor, team.id(), profile, usableConnections);
        Set<ModelConnectionId> usableIds = usableConnections.stream()
                .map(ModelConnection::id)
                .collect(Collectors.toUnmodifiableSet());
        Principal requestingPrincipal = executionScope == AgentExecutionScope.PERSONAL
                        && ownerUser.isPresent()
                ? ownerUser.orElseThrow()
                : actor;
        AgentExecutionAuthorizationFacts authorization = new AgentExecutionAuthorizationFacts(
                requestingPrincipal.id(),
                requestingPrincipal.canAct(),
                actorMember.canParticipate() && ownerMember.stream().allMatch(TeamMember::canParticipate),
                true,
                true,
                true,
                usableIds);
        return new SelectionFacts(
                actor,
                team,
                profile,
                executor,
                scopeFacts,
                policy,
                authorization,
                usableConnections);
    }

    private ResolveAgentExecutionConfigurationRequest resolutionRequest(
            SelectionFacts facts,
            TaskAgentSelectionRequest selection,
            UtcTimestamp resolvedAt) {
        return new ResolveAgentExecutionConfigurationRequest(
                facts.team().organizationId(),
                selection.agentProfileId(),
                selection.configurationRevision(),
                facts.scopeFacts(),
                facts.policy().policyConstraints(),
                facts.authorization(),
                resolvedAt);
    }

    private static AgentExecutionScopeFacts scopeFacts(
            Principal actor,
            TeamMember actorMember,
            AgentProfile profile,
            Principal executor,
            Optional<TeamMember> ownerMember,
            List<ResponsibilityAssignment> assignments) {
        boolean teamOwned = profile.ownership().type() != AgentOwnershipType.USER;
        boolean ownerIsActor = ownerMember.filter(value -> value.id().equals(actorMember.id()))
                .isPresent();
        boolean personalResponsibilityChain = ownerIsActor && assignments.stream()
                .filter(ResponsibilityAssignment::isActive)
                .filter(value -> value.role() == ResponsibilityRole.OWNER
                        || value.role() == ResponsibilityRole.EXECUTOR)
                .allMatch(value -> value.actorPrincipalId().equals(actor.id())
                        || value.actorPrincipalId().equals(executor.id()));
        return new AgentExecutionScopeFacts(
                teamOwned,
                !personalResponsibilityChain,
                false,
                false);
    }

    private List<ModelConnection> usableConnections(
            Team team,
            AgentProfile profile,
            Optional<Principal> ownerUser,
            AgentExecutionScope scope) {
        Map<ModelConnectionId, ModelConnection> result = new LinkedHashMap<>();
        if (scope == AgentExecutionScope.PERSONAL
                && profile.ownership().type() == AgentOwnershipType.USER) {
            Principal owner = ownerUser.orElseThrow(() -> new DomainValidationException(
                    "taskAgent.ownerMemberId", "must resolve the active USER owner"));
            connections.findByOwner(ModelConnectionOwner.user(owner))
                    .forEach(value -> result.put(value.id(), value));
        }
        if (profile.ownership().type() != AgentOwnershipType.ORGANIZATION) {
            connections.findByOwner(ModelConnectionOwner.team(team))
                    .forEach(value -> result.put(value.id(), value));
        }
        connections.findByOwner(ModelConnectionOwner.organization(team.organizationId()))
                .forEach(value -> result.put(value.id(), value));
        return result.values().stream()
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .toList();
    }

    private void requirePinnedConnection(
            SelectionFacts facts, ModelConnectionId connectionId, UtcTimestamp checkedAt) {
        ModelConnection connection = facts.usableConnections().stream()
                .filter(value -> value.id().equals(connectionId))
                .findFirst()
                .orElseThrow(() -> new AgentModelPreflightException(
                        AgentModelPreflightRejectionCode.CONNECTION_FORBIDDEN));
        availability.requireAvailable(
                connection, facts.authorization().requestingPrincipalId(), checkedAt);
    }

    private static void requireResolvedCoordinates(
            SelectionFacts facts, ResolvedAgentExecutionConfiguration resolved) {
        AgentExecutionScope expectedScope = AgentExecutionScopePolicy.resolve(facts.scopeFacts());
        if (!resolved.agentProfileId().equals(facts.profile().id())
                || resolved.agentProfileVersion() != facts.profile().version()
                || !resolved.agentPrincipalId().equals(facts.executor().id())
                || resolved.executionScope() != expectedScope) {
            throw new AgentModelPreflightException(
                    AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }
    }

    private TeamMember requireActiveMember(Team team, io.crewscope.domain.shared.id.PrincipalId id) {
        return memberships.findByTeam(team.organizationId(), team.id()).stream()
                .filter(value -> value.userPrincipalId().equals(id))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException("delegate a Task in this Team"));
    }

    private static Principal requireActor(TeamAccessContext context, WorkItem item) {
        Principal actor = Objects.requireNonNull(context, "context").actor();
        if (actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(item.scope().organizationId())) {
            throw new PolicyDeniedException("delegate a Task in this Organization");
        }
        return actor;
    }

    private record SelectionFacts(
            Principal actor,
            Team team,
            AgentProfile profile,
            Principal executor,
            AgentExecutionScopeFacts scopeFacts,
            AgentModelGovernanceSnapshot policy,
            AgentExecutionAuthorizationFacts authorization,
            List<ModelConnection> usableConnections) {

        private SelectionFacts {
            usableConnections = List.copyOf(usableConnections);
        }
    }
}
