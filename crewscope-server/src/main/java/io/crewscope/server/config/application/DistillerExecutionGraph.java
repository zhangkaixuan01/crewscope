package io.crewscope.server.config.application;

import io.crewscope.agentscope.distiller.DistillerKind;
import io.crewscope.agentscope.distiller.DistillerRuntimeSession;
import io.crewscope.agentscope.distiller.DistillerTemplateRuntimeRegistry;
import io.crewscope.agentscope.template.AgentTemplateRuntimeAssembler;
import io.crewscope.agentscope.template.AgentTemplateRuntimeDefinition;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentExecutionConfigurationService;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.agent.ResolveAgentExecutionConfigurationRequest;
import io.crewscope.application.distiller.DistillationAttemptUsage;
import io.crewscope.application.distiller.DistillationCallAttribution;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionScopeFacts;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.ResolvedModelSelection;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Exact TEAM-only model graph shared by the Knowledge and Skill distillation adapters
 * (M13-C04). Readiness has already run in the application service's validation
 * transaction, so a missing pair, configuration or exact template hash here is a 422
 * drift, never a provisioning trigger. The per-kind contract supplies the template
 * version, deterministic profile id, domain validation hooks and error strings.
 */
final class DistillerExecutionGraph {

    private final TeamRepository teams;
    private final AgentProfileRepository profiles;
    private final PrincipalRepository principals;
    private final AgentTemplateRepository templates;
    private final AgentConfigurationRepository configurations;
    private final ModelConnectionRepository connections;
    private final AgentModelGovernance governance;
    private final AgentExecutionConfigurationService resolver;
    private final AgentTemplateRuntimeAssembler assembler;
    private final DistillerTemplateRuntimeRegistry registry;
    private final TimeProvider timeProvider;

    DistillerExecutionGraph(
            TeamRepository teams,
            AgentProfileRepository profiles,
            PrincipalRepository principals,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            ModelConnectionRepository connections,
            AgentModelGovernance governance,
            AgentExecutionConfigurationService resolver,
            AgentTemplateRuntimeAssembler assembler,
            DistillerTemplateRuntimeRegistry registry,
            TimeProvider timeProvider) {
        this.teams = Objects.requireNonNull(teams, "teams");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    DistillerExecution resolve(
            OrganizationId organizationId,
            TeamId teamId,
            PrincipalId initiatedBy,
            UUID commandId,
            UUID correlationId,
            Contract contract) {
        Team team = teams.findById(organizationId, teamId)
                .filter(Team::isActive)
                .orElseThrow(() -> invalid(
                        "distillation.teamId", "must reference an active Team"));
        var profile = profiles.findById(
                        organizationId,
                        contract.stableProfileId().apply(teamId))
                .map(contract.requireProfile())
                .orElseThrow(() -> invalid(
                        contract.errorPrefix() + ".agentProfile",
                        contract.profileMissingMessage()));
        var configuration = configurations.findCurrent(organizationId, profile.id())
                .orElseThrow(() -> invalid(
                        contract.errorPrefix() + ".configuration",
                        contract.configurationMissingMessage()));
        AgentTemplateDefinition template =
                requireTemplate(profile, configuration.templateContentHash(), contract);

        List<ModelConnection> usableConnections = usableConnections(team);
        Set<ModelConnectionId> usableIds = usableConnections.stream()
                .map(ModelConnection::id)
                .collect(Collectors.toUnmodifiableSet());
        var actor = principals.findById(organizationId, initiatedBy)
                .orElseThrow(() -> invalid(
                        // Existence only: actability is enforced downstream by the
                        // governance resolution on this Principal.
                        "distillation.initiatedBy", "must reference a known Principal"));
        var policy = governance.resolve(actor, teamId, profile, usableConnections);
        ResolvedAgentExecutionConfiguration resolved = resolver.resolve(
                new ResolveAgentExecutionConfigurationRequest(
                        organizationId,
                        profile.id(),
                        Optional.of(configuration.revision()),
                        new AgentExecutionScopeFacts(true, true, true, true),
                        policy.policyConstraints(),
                        new AgentExecutionAuthorizationFacts(
                                actor.id(),
                                actor.canAct(),
                                true,
                                true,
                                true,
                                true,
                                usableIds),
                        timeProvider.now()));
        registry.requireCoordinates(profile, template, configuration, resolved);
        AgentTemplateRuntimeDefinition definition = assembler.assemble(
                profile, template, configuration, resolved,
                actor.id(), correlationId);
        DistillerRuntimeSession session = new DistillerRuntimeSession(
                contract.kind(),
                organizationId,
                teamId,
                initiatedBy,
                profile.agentPrincipalId(),
                profile.id(),
                profile.version(),
                commandId);
        return new DistillerExecution(definition, session, resolved.primary());
    }

    private AgentTemplateDefinition requireTemplate(
            AgentProfile profile, AgentTemplateHash expectedHash, Contract contract) {
        // Organization scope is the Distiller's only publisher, so one exact-hash match
        // or none — no team/org source merge like the Observer preflight performs.
        AgentTemplateDefinition match = templates.findByVersion(
                        AgentTemplatePublisherScope.organization(
                                profile.scope().organizationId()),
                        contract.version())
                .filter(value -> value.contentHash().equals(expectedHash))
                .orElseThrow(() -> invalid(
                        contract.errorPrefix() + ".template",
                        contract.templateMissingMessage()));
        return contract.requireDefinition().apply(match);
    }

    /** USER connections never enter Distiller preflight or credential resolution. */
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

    /** The per-kind differences of the resolution chain. */
    record Contract(
            DistillerKind kind,
            AgentTemplateVersion version,
            Function<TeamId, AgentProfileId> stableProfileId,
            UnaryOperator<AgentProfile> requireProfile,
            UnaryOperator<AgentTemplateDefinition> requireDefinition,
            String errorPrefix,
            String profileMissingMessage,
            String configurationMissingMessage,
            String templateMissingMessage) {
        Contract {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(stableProfileId, "stableProfileId");
            Objects.requireNonNull(requireProfile, "requireProfile");
            Objects.requireNonNull(requireDefinition, "requireDefinition");
            Objects.requireNonNull(errorPrefix, "errorPrefix");
            Objects.requireNonNull(profileMissingMessage, "profileMissingMessage");
            Objects.requireNonNull(configurationMissingMessage, "configurationMissingMessage");
            Objects.requireNonNull(templateMissingMessage, "templateMissingMessage");
        }
    }

    /** Everything one distillation call needs after resolution. */
    record DistillerExecution(
            AgentTemplateRuntimeDefinition definition,
            DistillerRuntimeSession session,
            ResolvedModelSelection selection) {

        DistillationCallAttribution attribution(
                Optional<String> observedModelName, List<DistillationAttemptUsage> attempts) {
            return new DistillationCallAttribution(
                    selection.providerKey(),
                    observedModelName
                            .map(ModelId::new)
                            .orElseGet(() -> selection.catalogCoordinate().modelId()),
                    selection.connectionId(),
                    selection.connectionVersion(),
                    attempts);
        }
    }
}
