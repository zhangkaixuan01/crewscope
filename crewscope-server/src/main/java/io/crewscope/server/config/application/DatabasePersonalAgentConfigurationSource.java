package io.crewscope.server.config.application;

import io.crewscope.agentscope.AgentScopePersonalAgentConfiguration;
import io.crewscope.agentscope.PersonalAgentConfigurationSource;
import io.crewscope.agentscope.template.AgentTemplateRuntimeAssembler;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentExecutionConfigurationService;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.AgentModelGovernanceSnapshot;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.agent.ResolveAgentExecutionConfigurationRequest;
import io.crewscope.application.execution.PlatformExecutionContext;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionScopeFacts;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Loads Personal Agent models from the persisted per-user configuration and CredentialStore. */
final class DatabasePersonalAgentConfigurationSource implements PersonalAgentConfigurationSource {

    private final AgentProfileRepository profiles;
    private final AgentTemplateRepository templates;
    private final AgentConfigurationRepository configurations;
    private final AgentExecutionConfigurationService executionConfigurations;
    private final AgentModelGovernance governance;
    private final ModelConnectionRepository connections;
    private final PrincipalRepository principals;
    private final TeamRepository teams;
    private final AgentTemplateRuntimeAssembler assembler;
    private final PersonalAgentRuntimeProperties properties;
    private final TimeProvider timeProvider;

    DatabasePersonalAgentConfigurationSource(
            AgentProfileRepository profiles,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            AgentExecutionConfigurationService executionConfigurations,
            AgentModelGovernance governance,
            ModelConnectionRepository connections,
            PrincipalRepository principals,
            TeamRepository teams,
            AgentTemplateRuntimeAssembler assembler,
            PersonalAgentRuntimeProperties properties,
            TimeProvider timeProvider) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.executionConfigurations = Objects.requireNonNull(
                executionConfigurations, "executionConfigurations");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    @Override
    public AgentScopePersonalAgentConfiguration load(
            io.crewscope.domain.workspace.AgentProfileId profileId, long profileVersion) {
        // The invocation-aware overload is required in production so model credentials remain
        // scoped to the authenticated user. Keep this compatibility path for non-runtime callers.
        return new AgentScopePersonalAgentConfiguration(
                profileId,
                profileVersion,
                properties.getModelId(),
                properties.fallbackModelId(),
                properties.getSystemPrompt(),
                properties.getMaxIterations(),
                properties.getMaxRetries());
    }

    @Override
    public AgentScopePersonalAgentConfiguration load(
            AgentRuntimeSession session, PlatformExecutionContext context) {
        AgentRuntimeSession requiredSession = Objects.requireNonNull(session, "session");
        PlatformExecutionContext trustedContext = Objects.requireNonNull(
                context, "platformContext");
        AgentProfile profile = profiles.findById(
                        requiredSession.scope().organizationId(), requiredSession.agentProfileId())
                .filter(value -> value.version() == requiredSession.agentProfileVersion())
                .orElseThrow(() -> new IllegalStateException(
                        "Personal Agent profile is unavailable"));
        var configuration = requiredSession.configurationPin()
                .flatMap(pin -> pin.configurationRevision().flatMap(revision ->
                        configurations.findByRevision(
                                requiredSession.scope().organizationId(), profile.id(), revision)))
                .orElseGet(() -> configurations.findCurrent(
                        requiredSession.scope().organizationId(), profile.id()).orElseThrow(
                                () -> new IllegalStateException(
                                        "Personal Agent configuration is unavailable")));
        requiredSession.configurationPin().ifPresent(pin -> {
            if (pin.configurationHash().isEmpty()
                    || !pin.configurationHash().orElseThrow()
                            .equals(configuration.configurationHash())) {
                throw new IllegalStateException("Personal Agent configuration pin is stale");
            }
        });
        AgentTemplateDefinition template = exactTemplate(
                profile, configuration.templateVersion(), configuration.templateContentHash());
        var actor = principals.findById(
                        requiredSession.scope().organizationId(), trustedContext.requestPrincipalId())
                .orElseThrow(() -> new IllegalStateException("Personal Agent actor is unavailable"));
        var team = teams.findById(
                        requiredSession.scope().organizationId(), requiredSession.scope().teamId())
                .orElseThrow(() -> new IllegalStateException("Personal Agent team is unavailable"));
        List<ModelConnection> usable = usableConnections(actor, team);
        AgentModelGovernanceSnapshot policy = governance.resolve(actor, team.id(), profile, usable);
        Set<ModelConnectionId> usableIds = usable.stream()
                .map(ModelConnection::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var resolved = executionConfigurations.resolve(
                new ResolveAgentExecutionConfigurationRequest(
                        requiredSession.scope().organizationId(),
                        profile.id(),
                        requiredSession.configurationPin().flatMap(
                                pin -> pin.configurationRevision()),
                        new AgentExecutionScopeFacts(false, false, false, false),
                        policy.policyConstraints(),
                        new AgentExecutionAuthorizationFacts(
                                actor.id(), actor.canAct(), true, true, true, true, usableIds),
                        timeProvider.now()));
        var definition = assembler.assemble(
                profile,
                template,
                configuration,
                resolved,
                actor.id(),
                trustedContext.correlationId());
        return new AgentScopePersonalAgentConfiguration(
                profile.id(),
                profile.version(),
                "database-primary",
                Optional.empty(),
                definition.systemPrompt(),
                properties.getMaxIterations(),
                configuration.generateOptions().maximumAttempts(),
                Optional.of(definition.primaryModel()),
                definition.fallbackModel());
    }

    private List<ModelConnection> usableConnections(
            io.crewscope.domain.identity.Principal actor,
            io.crewscope.domain.team.Team team) {
        MapBuilder result = new MapBuilder();
        connections.findByOwner(ModelConnectionOwner.user(actor)).forEach(result::put);
        connections.findByOwner(ModelConnectionOwner.team(team)).forEach(result::put);
        connections.findByOwner(ModelConnectionOwner.organization(team.organizationId()))
                .forEach(result::put);
        return result.values();
    }

    private AgentTemplateDefinition exactTemplate(
            AgentProfile profile,
            io.crewscope.domain.agent.AgentTemplateVersion version,
            io.crewscope.domain.agent.AgentTemplateHash hash) {
        List<AgentTemplateDefinition> matches = new ArrayList<>();
        profile.scope().teamId().flatMap(teamId -> templates.findByVersion(
                        AgentTemplatePublisherScope.team(
                                profile.scope().organizationId(), teamId),
                        version))
                .filter(value -> value.contentHash().equals(hash))
                .ifPresent(matches::add);
        templates.findByVersion(
                        AgentTemplatePublisherScope.organization(
                                profile.scope().organizationId()),
                        version)
                .filter(value -> value.contentHash().equals(hash))
                .ifPresent(matches::add);
        if (matches.size() != 1) {
            throw new IllegalStateException("Personal Agent template is unavailable");
        }
        return matches.get(0);
    }

    private static final class MapBuilder {
        private final LinkedHashMap<ModelConnectionId, ModelConnection> values =
                new LinkedHashMap<>();

        void put(ModelConnection value) {
            values.put(value.id(), value);
        }

        List<ModelConnection> values() {
            return List.copyOf(values.values());
        }
    }
}
