package io.crewscope.server.config.application;

import io.crewscope.agentscope.distiller.DistillerKind;
import io.crewscope.agentscope.distiller.DistillerTemplateRuntimeRegistry;
import io.crewscope.agentscope.skill.SkillDistillerRuntime;
import io.crewscope.agentscope.skill.SkillDistillerRuntimeRequest;
import io.crewscope.agentscope.template.AgentTemplateRuntimeAssembler;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentExecutionConfigurationService;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.skill.SkillDistillationPort;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.skill.distiller.SkillDistillerInitialization;
import io.crewscope.domain.skill.distiller.SkillDistillerTemplate;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Resolves the Skill Distiller's exact TEAM-only model graph through the shared
 * execution graph and delegates one single-turn structured-output call to AgentScope
 * (M10-A03b, M13-C04). Readiness has already run in the application service's validation
 * transaction, so a missing pair or configuration here is a 422 drift, never a
 * provisioning trigger. The skill key travels with the request (D4) — the model only
 * produces the description and body.
 */
final class AgentScopeSkillDistillationAdapter implements SkillDistillationPort {

    private static final DistillerExecutionGraph.Contract CONTRACT =
            new DistillerExecutionGraph.Contract(
                    DistillerKind.SKILL,
                    SkillDistillerTemplate.VERSION,
                    SkillDistillerInitialization::stableProfileId,
                    SkillDistillerTemplate::requireProfile,
                    SkillDistillerTemplate::requireDefinition,
                    "skillDistiller",
                    "the Team Skill Distiller is not active",
                    "the Team Skill Distiller has no current configuration",
                    "must resolve one exact skill-distiller@1 definition");

    private final SkillDistillerRuntime runtime;
    private final DistillerExecutionGraph graph;

    AgentScopeSkillDistillationAdapter(
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
            SkillDistillerRuntime runtime,
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
    public CompletionStage<SkillDistillationResult> distill(SkillDistillationRequest request) {
        SkillDistillationRequest required = Objects.requireNonNull(request, "request");
        DistillerExecutionGraph.DistillerExecution execution = graph.resolve(
                required.organizationId(),
                required.teamId(),
                required.initiatedBy(),
                required.commandId(),
                required.correlationId(),
                CONTRACT);
        return runtime.distill(new SkillDistillerRuntimeRequest(
                        execution.definition(),
                        execution.session(),
                        required.taskExecutionId(),
                        required.executionAttempt(),
                        required.skillKey().value(),
                        required.sanitizedSourceText()))
                .map(observation -> new SkillDistillationResult(
                        observation.draft(),
                        execution.attribution(
                                observation.observedModelName(), observation.attempts())))
                .toFuture();
    }
}
