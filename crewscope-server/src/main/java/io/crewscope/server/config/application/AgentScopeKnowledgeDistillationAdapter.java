package io.crewscope.server.config.application;

import io.crewscope.agentscope.knowledge.KnowledgeDistillerRuntime;
import io.crewscope.agentscope.knowledge.KnowledgeDistillerRuntimeRequest;
import io.crewscope.agentscope.knowledge.KnowledgeDistillerRuntimeSession;
import io.crewscope.agentscope.knowledge.KnowledgeDistillerTemplateRuntimeRegistry;
import io.crewscope.agentscope.template.AgentTemplateRuntimeAssembler;
import io.crewscope.agentscope.template.AgentTemplateRuntimeDefinition;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentExecutionConfigurationService;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.agent.ResolveAgentExecutionConfigurationRequest;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.knowledge.KnowledgeDistillationPort;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionScopeFacts;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerInitialization;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerTemplate;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.Team;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * Resolves the Distiller's exact TEAM-only model graph and delegates one single-turn
 * structured-output call to AgentScope. Mirrors the Team Observer resolution chain minus
 * readiness provisioning: the application service has already run {@code ensureReady} in
 * its validation transaction, so a missing pair or configuration here is a 422 drift,
 * never a provisioning trigger.
 */
final class AgentScopeKnowledgeDistillationAdapter implements KnowledgeDistillationPort {

    private final TeamRepository teams;
    private final AgentProfileRepository profiles;
    private final PrincipalRepository principals;
    private final AgentTemplateRepository templates;
    private final AgentConfigurationRepository configurations;
    private final ModelConnectionRepository connections;
    private final AgentModelGovernance governance;
    private final AgentExecutionConfigurationService resolver;
    private final AgentTemplateRuntimeAssembler assembler;
    private final KnowledgeDistillerTemplateRuntimeRegistry registry;
    private final KnowledgeDistillerRuntime runtime;
    private final TimeProvider timeProvider;

    AgentScopeKnowledgeDistillationAdapter(
            TeamRepository teams,
            AgentProfileRepository profiles,
            PrincipalRepository principals,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            ModelConnectionRepository connections,
            AgentModelGovernance governance,
            AgentExecutionConfigurationService resolver,
            AgentTemplateRuntimeAssembler assembler,
            KnowledgeDistillerTemplateRuntimeRegistry registry,
            KnowledgeDistillerRuntime runtime,
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
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    @Override
    public CompletionStage<KnowledgeDistillationResult> distill(
            KnowledgeDistillationRequest request) {
        KnowledgeDistillationRequest required = Objects.requireNonNull(request, "request");
        Team team = teams.findById(required.organizationId(), required.teamId())
                .filter(Team::isActive)
                .orElseThrow(() -> invalid(
                        "distillation.teamId", "must reference an active Team"));
        var profile = profiles.findById(
                        required.organizationId(),
                        KnowledgeDistillerInitialization.stableProfileId(required.teamId()))
                .map(KnowledgeDistillerTemplate::requireProfile)
                .orElseThrow(() -> invalid(
                        "knowledgeDistiller.agentProfile",
                        "the Team Distiller is not active"));
        var configuration = configurations.findCurrent(
                        required.organizationId(), profile.id())
                .orElseThrow(() -> invalid(
                        "knowledgeDistiller.configuration",
                        "the Team Distiller has no current configuration"));
        AgentTemplateDefinition template =
                requireTemplate(profile, configuration.templateContentHash());

        List<ModelConnection> usableConnections = usableConnections(team);
        Set<ModelConnectionId> usableIds = usableConnections.stream()
                .map(ModelConnection::id)
                .collect(Collectors.toUnmodifiableSet());
        var actor = principals.findById(required.organizationId(), required.initiatedBy())
                .orElseThrow(() -> invalid(
                        // Existence only: actability is enforced downstream by the
                        // governance resolution on this Principal.
                        "distillation.initiatedBy", "must reference a known Principal"));
        var policy = governance.resolve(actor, required.teamId(), profile, usableConnections);
        ResolvedAgentExecutionConfiguration resolved = resolver.resolve(
                new ResolveAgentExecutionConfigurationRequest(
                        required.organizationId(),
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
                actor.id(), required.correlationId());
        KnowledgeDistillerRuntimeSession session = new KnowledgeDistillerRuntimeSession(
                required.organizationId(),
                required.teamId(),
                required.initiatedBy(),
                profile.agentPrincipalId(),
                profile.id(),
                profile.version(),
                required.commandId());
        var selection = resolved.primary();
        return runtime.distill(new KnowledgeDistillerRuntimeRequest(
                        definition,
                        session,
                        required.taskExecutionId(),
                        required.executionAttempt(),
                        required.sanitizedSourceText(),
                        required.requestedCategory()
                                .map(category -> category.name())
                                .orElse(KnowledgeDistillerRuntimeRequest.MODEL_SUGGESTED_CATEGORY)))
                .map(observation -> new KnowledgeDistillationResult(
                        observation.draft(),
                        new CallAttribution(
                                selection.providerKey(),
                                observation.observedModelName()
                                        .map(ModelId::new)
                                        .orElseGet(() ->
                                                selection.catalogCoordinate().modelId()),
                                selection.connectionId(),
                                selection.connectionVersion(),
                                observation.attempts())))
                .toFuture();
    }

    private AgentTemplateDefinition requireTemplate(
            io.crewscope.domain.workspace.AgentProfile profile,
            io.crewscope.domain.agent.AgentTemplateHash expectedHash) {
        // Organization scope is the Distiller's only publisher, so one exact-hash match
        // or none — no team/org source merge like the Observer preflight performs.
        AgentTemplateDefinition match = templates.findByVersion(
                        AgentTemplatePublisherScope.organization(
                                profile.scope().organizationId()),
                        KnowledgeDistillerTemplate.VERSION)
                .filter(value -> value.contentHash().equals(expectedHash))
                .orElseThrow(() -> invalid(
                        "knowledgeDistiller.template",
                        "must resolve one exact knowledge-distiller@1 definition"));
        return KnowledgeDistillerTemplate.requireDefinition(match);
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
}
