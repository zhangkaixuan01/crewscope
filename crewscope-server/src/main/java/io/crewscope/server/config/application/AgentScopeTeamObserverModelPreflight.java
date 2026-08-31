package io.crewscope.server.config.application;

import io.crewscope.application.agent.AgentExecutionConfigurationResolver;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.teamobserver.TeamObserverModelPreflight;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionScopeFacts;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Reuses the production model resolver to close the Observer activation gate. */
final class AgentScopeTeamObserverModelPreflight implements TeamObserverModelPreflight {

    private final TeamRepository teams;
    private final AgentProfileRepository profiles;
    private final AgentTemplateRepository templates;
    private final PrincipalRepository principals;
    private final ModelConnectionRepository connections;
    private final AgentModelGovernance governance;
    private final AgentExecutionConfigurationResolver resolver;
    private final TimeProvider timeProvider;

    AgentScopeTeamObserverModelPreflight(
            TeamRepository teams,
            AgentProfileRepository profiles,
            AgentTemplateRepository templates,
            PrincipalRepository principals,
            ModelConnectionRepository connections,
            AgentModelGovernance governance,
            AgentExecutionConfigurationResolver resolver,
            TimeProvider timeProvider) {
        this.teams = java.util.Objects.requireNonNull(teams, "teams");
        this.profiles = java.util.Objects.requireNonNull(profiles, "profiles");
        this.templates = java.util.Objects.requireNonNull(templates, "templates");
        this.principals = java.util.Objects.requireNonNull(principals, "principals");
        this.connections = java.util.Objects.requireNonNull(connections, "connections");
        this.governance = java.util.Objects.requireNonNull(governance, "governance");
        this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
        this.timeProvider = java.util.Objects.requireNonNull(timeProvider, "timeProvider");
    }

    @Override
    public void requireReady(
            OrganizationId organizationId,
            TeamId teamId,
            AgentConfigurationVersion configuration) {
        var team = teams.findById(organizationId, teamId)
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
        var profile = profiles.findById(organizationId, configuration.agentProfileId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "AgentProfile", configuration.agentProfileId()));
        var actorId = configuration.audit().updatedBy()
                .or(() -> configuration.audit().createdBy())
                .orElseThrow();
        var actor = principals.findById(organizationId, actorId)
                .orElseThrow(() -> new AggregateNotFoundException("Principal", actorId));
        var template = templates
                .findByVersion(
                        AgentTemplatePublisherScope.organization(organizationId),
                        configuration.templateVersion())
                .filter(value -> value.contentHash().equals(configuration.templateContentHash()))
                .orElseThrow();
        var activationProfile = profile.status()
                        == io.crewscope.domain.workspace.AgentProfileStatus.ACTIVE
                ? profile
                : profile.activate(actor.id(), timeProvider.now());
        Map<ModelConnectionId, ModelConnection> usable = new LinkedHashMap<>();
        connections.findByOwner(ModelConnectionOwner.team(team))
                .forEach(value -> usable.put(value.id(), value));
        connections.findByOwner(ModelConnectionOwner.organization(organizationId))
                .forEach(value -> usable.put(value.id(), value));
        var snapshot = governance.resolve(
                actor, teamId, activationProfile, usable.values().stream().toList());
        Set<ModelConnectionId> usableIds = usable.keySet().stream().collect(Collectors.toUnmodifiableSet());
        resolver.resolve(
                activationProfile,
                template,
                configuration,
                new AgentExecutionScopeFacts(true, true, true, true),
                snapshot.policyConstraints(),
                new AgentExecutionAuthorizationFacts(
                        actor.id(), actor.canAct(), true, true, true, true, usableIds),
                timeProvider.now());
    }
}
