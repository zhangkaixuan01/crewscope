package io.crewscope.agentscope.template;

import io.crewscope.agentscope.model.ResolvedAgentScopeModelFactory;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.Objects;
import java.util.UUID;

/** Assembles one executable Template graph after application preflight has closed its coordinates. */
public final class AgentTemplateRuntimeAssembler {

    private final ResolvedAgentScopeModelFactory models;

    public AgentTemplateRuntimeAssembler(ResolvedAgentScopeModelFactory models) {
        this.models = Objects.requireNonNull(models, "models");
    }

    public AgentTemplateRuntimeDefinition assemble(
            AgentProfile profile,
            AgentTemplateDefinition template,
            AgentConfigurationVersion configuration,
            ResolvedAgentExecutionConfiguration resolved,
            PrincipalId actor,
            UUID correlationId) {
        AgentProfile requiredProfile = Objects.requireNonNull(profile, "profile");
        AgentConfigurationVersion requiredConfiguration = Objects.requireNonNull(
                configuration, "configuration");
        return new AgentTemplateRuntimeDefinition(
                requiredProfile,
                template,
                requiredConfiguration,
                resolved,
                models.build(
                        requiredProfile.scope().organizationId(),
                        resolved,
                        requiredConfiguration.generateOptions(),
                        actor,
                        correlationId));
    }
}
