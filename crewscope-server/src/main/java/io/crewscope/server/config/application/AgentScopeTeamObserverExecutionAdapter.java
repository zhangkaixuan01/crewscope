package io.crewscope.server.config.application;

import io.crewscope.agentscope.teamobserver.TeamObserverModelFactory;
import io.crewscope.agentscope.teamobserver.TeamObserverRuntime;
import io.crewscope.agentscope.teamobserver.TeamObserverRuntimeRequest;
import io.crewscope.agentscope.teamobserver.TeamObserverRuntimeSession;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentExecutionConfigurationService;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.agent.ResolveAgentExecutionConfigurationRequest;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.teamobserver.TeamObserverExecution;
import io.crewscope.application.teamobserver.TeamObserverExecutionPort;
import io.crewscope.application.teamobserver.TeamObserverExecutionRequest;
import io.crewscope.application.teamobserver.TeamObserverReadiness;
import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionScopeFacts;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;
import io.crewscope.domain.teamobserver.TeamObserverTemplate;
import io.crewscope.domain.teamobserver.TeamSummaryRequest;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import reactor.core.Disposable;

/** Resolves an exact TEAM-only model graph and delegates one summary to AgentScope Java. */
final class AgentScopeTeamObserverExecutionAdapter implements TeamObserverExecutionPort {

    private final TeamRepository teams;
    private final AgentProfileRepository profiles;
    private final AgentTemplateRepository templates;
    private final AgentConfigurationRepository configurations;
    private final ModelConnectionRepository connections;
    private final AgentModelGovernance governance;
    private final AgentExecutionConfigurationService resolver;
    private final TeamObserverModelFactory models;
    private final TeamObserverRuntime runtime;
    private final TeamObserverReadiness provisioning;
    private final io.crewscope.domain.shared.time.TimeProvider timeProvider;

    AgentScopeTeamObserverExecutionAdapter(
            TeamRepository teams,
            AgentProfileRepository profiles,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            ModelConnectionRepository connections,
            AgentModelGovernance governance,
            AgentExecutionConfigurationService resolver,
            TeamObserverModelFactory models,
            TeamObserverRuntime runtime,
            TeamObserverReadiness provisioning,
            io.crewscope.domain.shared.time.TimeProvider timeProvider) {
        this.teams = Objects.requireNonNull(teams, "teams");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.models = Objects.requireNonNull(models, "models");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.provisioning = Objects.requireNonNull(provisioning, "provisioning");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    @Override
    public TeamObserverExecution execute(TeamObserverExecutionRequest request) {
        TeamObserverExecutionRequest required = Objects.requireNonNull(request, "request");
        Team team = teams.findById(required.organizationId(), required.teamId())
                .filter(Team::isActive)
                .orElseThrow(() -> invalid("teamObserver.teamId", "must reference an active Team"));
        provisioning.ensureReady(required.organizationId(), required.teamId());
        AgentProfile profile = profiles.findById(
                        required.organizationId(),
                        TeamObserverInitialization.stableProfileId(required.teamId()))
                .map(TeamObserverTemplate::requireProfile)
                .orElseThrow(() -> invalid(
                        "teamObserver.agentProfile", "the Team Observer is not active"));
        var configuration = configurations.findCurrent(
                        required.organizationId(), profile.id())
                .orElseThrow(() -> invalid(
                        "teamObserver.configuration", "the Team Observer has no current configuration"));
        AgentTemplateDefinition template = requireTemplate(profile, configuration.templateContentHash());

        List<ModelConnection> usableConnections = usableConnections(team);
        Set<ModelConnectionId> usableIds = usableConnections.stream()
                .map(ModelConnection::id)
                .collect(Collectors.toUnmodifiableSet());
        var policy = governance.resolve(
                required.actor(), required.teamId(), profile, usableConnections);
        var resolved = resolver.resolve(new ResolveAgentExecutionConfigurationRequest(
                required.organizationId(),
                profile.id(),
                java.util.Optional.of(configuration.revision()),
                new AgentExecutionScopeFacts(true, true, true, true),
                policy.policyConstraints(),
                new AgentExecutionAuthorizationFacts(
                        required.actor().id(),
                        required.actor().canAct(),
                        true,
                        true,
                        true,
                        true,
                        usableIds),
                timeProvider.now()));
        var definition = models.build(
                profile,
                template,
                configuration,
                resolved,
                required.actor().id(),
                required.correlationId());
        TeamObserverRuntimeSession session = new TeamObserverRuntimeSession(
                required.organizationId(),
                required.teamId(),
                required.memberId(),
                profile.agentPrincipalId(),
                profile.id(),
                profile.version(),
                required.sessionId().value());
        CompletableFuture<io.crewscope.domain.teamobserver.TeamSummaryResult> result =
                new CompletableFuture<>();
        Disposable subscription = runtime.summarize(new TeamObserverRuntimeRequest(
                        definition,
                        session,
                        new TeamSummaryRequest(
                                required.organizationId(),
                                required.teamId(),
                                required.memberId(),
                                required.maxItemsPerSection()),
                        required.instruction()))
                .subscribe(result::complete, result::completeExceptionally);
        return new TeamObserverExecution(result, () -> {
            subscription.dispose();
            result.cancel(false);
        });
    }

    private AgentTemplateDefinition requireTemplate(
            AgentProfile profile, io.crewscope.domain.agent.AgentTemplateHash expectedHash) {
        List<AgentTemplateDefinition> matches = new ArrayList<>();
        profile.scope().teamId().flatMap(teamId -> templates.findByVersion(
                        AgentTemplatePublisherScope.team(
                                profile.scope().organizationId(), teamId),
                        TeamObserverTemplate.VERSION))
                .filter(value -> value.contentHash().equals(expectedHash))
                .ifPresent(matches::add);
        templates.findByVersion(
                        AgentTemplatePublisherScope.organization(
                                profile.scope().organizationId()),
                        TeamObserverTemplate.VERSION)
                .filter(value -> value.contentHash().equals(expectedHash))
                .ifPresent(matches::add);
        if (matches.size() != 1) {
            throw invalid(
                    "teamObserver.template", "must resolve one exact team-observer@1 definition");
        }
        return TeamObserverTemplate.requireDefinition(matches.get(0));
    }

    /** USER connections never enter Team Observer preflight or credential resolution. */
    List<ModelConnection> usableConnections(Team team) {
        Map<ModelConnectionId, ModelConnection> result = new LinkedHashMap<>();
        connections.findByOwner(ModelConnectionOwner.team(team))
                .forEach(value -> result.put(value.id(), value));
        connections.findByOwner(ModelConnectionOwner.organization(team.organizationId()))
                .forEach(value -> result.put(value.id(), value));
        return result.values().stream()
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .toList();
    }

    private static DomainValidationException invalid(String field, String message) {
        return new DomainValidationException(field, message);
    }
}
