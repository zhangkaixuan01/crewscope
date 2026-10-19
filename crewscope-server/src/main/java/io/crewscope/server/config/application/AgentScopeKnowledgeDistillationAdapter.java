package io.crewscope.server.config.application;

import io.crewscope.agentscope.distiller.DistillerKind;
import io.crewscope.agentscope.distiller.DistillerTemplateRuntimeRegistry;
import io.crewscope.agentscope.knowledge.KnowledgeDistillerRuntime;
import io.crewscope.agentscope.knowledge.KnowledgeDistillerRuntimeRequest;
import io.crewscope.agentscope.template.AgentTemplateRuntimeAssembler;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentExecutionConfigurationService;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.knowledge.KnowledgeDistillationPort;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerInitialization;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerTemplate;
import io.crewscope.domain.shared.time.TimeProvider;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Resolves the Distiller's exact TEAM-only model graph through the shared execution
 * graph and delegates one single-turn structured-output call to AgentScope. Readiness
 * has already run in the application service's validation transaction, so a missing pair
 * or configuration here is a 422 drift, never a provisioning trigger (M13-C04).
 */
final class AgentScopeKnowledgeDistillationAdapter implements KnowledgeDistillationPort {

    private static final DistillerExecutionGraph.Contract CONTRACT =
            new DistillerExecutionGraph.Contract(
                    DistillerKind.KNOWLEDGE,
                    KnowledgeDistillerTemplate.VERSION,
                    KnowledgeDistillerInitialization::stableProfileId,
                    KnowledgeDistillerTemplate::requireProfile,
                    KnowledgeDistillerTemplate::requireDefinition,
                    "knowledgeDistiller",
                    "the Team Distiller is not active",
                    "the Team Distiller has no current configuration",
                    "must resolve one exact knowledge-distiller@1 definition");

    private final KnowledgeDistillerRuntime runtime;
    private final DistillerExecutionGraph graph;

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
            DistillerTemplateRuntimeRegistry registry,
            KnowledgeDistillerRuntime runtime,
            TimeProvider timeProvider) {
        TeamRepository requiredTeams = Objects.requireNonNull(teams, "teams");
        AgentProfileRepository requiredProfiles = Objects.requireNonNull(profiles, "profiles");
        PrincipalRepository requiredPrincipals = Objects.requireNonNull(principals, "principals");
        AgentTemplateRepository requiredTemplates = Objects.requireNonNull(templates, "templates");
        AgentConfigurationRepository requiredConfigurations =
                Objects.requireNonNull(configurations, "configurations");
        ModelConnectionRepository requiredConnections =
                Objects.requireNonNull(connections, "connections");
        AgentModelGovernance requiredGovernance =
                Objects.requireNonNull(governance, "governance");
        AgentExecutionConfigurationService requiredResolver =
                Objects.requireNonNull(resolver, "resolver");
        AgentTemplateRuntimeAssembler requiredAssembler =
                Objects.requireNonNull(assembler, "assembler");
        DistillerTemplateRuntimeRegistry requiredRegistry =
                Objects.requireNonNull(registry, "registry");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        TimeProvider requiredTimeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.graph = new DistillerExecutionGraph(
                requiredTeams,
                requiredProfiles,
                requiredPrincipals,
                requiredTemplates,
                requiredConfigurations,
                requiredConnections,
                requiredGovernance,
                requiredResolver,
                requiredAssembler,
                requiredRegistry,
                requiredTimeProvider);
    }

    @Override
    public CompletionStage<KnowledgeDistillationResult> distill(
            KnowledgeDistillationRequest request) {
        KnowledgeDistillationRequest required = Objects.requireNonNull(request, "request");
        DistillerExecutionGraph.DistillerExecution execution = graph.resolve(
                required.organizationId(),
                required.teamId(),
                required.initiatedBy(),
                required.commandId(),
                required.correlationId(),
                CONTRACT);
        return runtime.distill(new KnowledgeDistillerRuntimeRequest(
                        execution.definition(),
                        execution.session(),
                        required.taskExecutionId(),
                        required.executionAttempt(),
                        required.sanitizedSourceText(),
                        required.requestedCategory()
                                .map(category -> category.name())
                                .orElse(KnowledgeDistillerRuntimeRequest.MODEL_SUGGESTED_CATEGORY)))
                .map(observation -> new KnowledgeDistillationResult(
                        observation.draft(),
                        execution.attribution(
                                observation.observedModelName(), observation.attempts())))
                .toFuture();
    }
}
