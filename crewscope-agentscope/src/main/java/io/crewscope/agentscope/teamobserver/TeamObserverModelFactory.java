package io.crewscope.agentscope.teamobserver;

import io.crewscope.agentscope.template.AgentTemplateRuntimeAssembler;
import io.crewscope.agentscope.template.AgentTemplateRuntimeDefinition;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.teamobserver.TeamObserverTemplate;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.Objects;
import java.util.UUID;

/** Materializes only preflighted TEAM/ORGANIZATION models for the built-in Observer. */
public final class TeamObserverModelFactory {

    private final AgentTemplateRuntimeAssembler assembler;
    private final TeamObserverTemplateRuntimeRegistry templates;

    public TeamObserverModelFactory(
            AgentTemplateRuntimeAssembler assembler,
            TeamObserverTemplateRuntimeRegistry templates) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.templates = Objects.requireNonNull(templates, "templates");
    }

    public AgentTemplateRuntimeDefinition build(
            AgentProfile profile,
            AgentTemplateDefinition template,
            AgentConfigurationVersion configuration,
            ResolvedAgentExecutionConfiguration resolved,
            PrincipalId actor,
            UUID correlationId) {
        AgentProfile requiredProfile = TeamObserverTemplate.requireProfile(profile);
        AgentTemplateDefinition requiredTemplate = TeamObserverTemplate.requireDefinition(template);
        AgentConfigurationVersion requiredConfiguration = Objects.requireNonNull(
                configuration, "configuration");
        ResolvedAgentExecutionConfiguration requiredResolved = Objects.requireNonNull(
                resolved, "resolved");
        templates.requireCoordinates(
                requiredProfile, requiredTemplate, requiredConfiguration, requiredResolved);
        return templates.requireRuntime(assembler.assemble(
                requiredProfile,
                requiredTemplate,
                requiredConfiguration,
                requiredResolved,
                Objects.requireNonNull(actor, "actor"),
                Objects.requireNonNull(correlationId, "correlationId")));
    }
}
