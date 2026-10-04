package io.crewscope.agentscope.skill;

import io.crewscope.agentscope.template.AgentTemplateRuntimeDefinition;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.model.ModelConnectionOwnerType;
import io.crewscope.domain.skill.distiller.SkillDistillerTemplate;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileStatus;
import java.util.Objects;

/**
 * Fail-closed registry for the single built-in Skill Distiller runtime contract
 * (M10-A03b): the exact TEAM model graph, zero Tools and the frozen distillation
 * output schema — anything else must never reach a credential handle.
 */
public final class SkillDistillerTemplateRuntimeRegistry {

    private static final AgentTemplateHash OUTPUT_SCHEMA_HASH =
            AgentTemplateHash.sha256(SkillDistillerTemplate.outputSchema());

    public AgentTemplateRuntimeDefinition requireRuntime(
            AgentTemplateRuntimeDefinition definition) {
        AgentTemplateRuntimeDefinition required = Objects.requireNonNull(definition, "definition");
        requireCoordinates(
                required.profile(),
                required.template(),
                required.configuration(),
                required.resolved());
        if (!required.enabledToolNames().isEmpty()) {
            throw new IllegalArgumentException(
                    "Skill Distiller Runtime requires the exact tool-less TEAM contract");
        }
        return required;
    }

    /** Validates all non-secret coordinates before the model factory opens a credential handle. */
    public void requireCoordinates(
            AgentProfile profile,
            AgentTemplateDefinition template,
            AgentConfigurationVersion configuration,
            ResolvedAgentExecutionConfiguration resolved) {
        AgentProfile requiredProfile = SkillDistillerTemplate.requireProfile(profile);
        AgentTemplateDefinition requiredTemplate = SkillDistillerTemplate.requireDefinition(
                template);
        AgentConfigurationVersion requiredConfiguration = Objects.requireNonNull(
                configuration, "configuration");
        ResolvedAgentExecutionConfiguration requiredResolved = Objects.requireNonNull(
                resolved, "resolved");
        boolean exactGraph = requiredProfile.status() == AgentProfileStatus.ACTIVE
                && requiredConfiguration.organizationId()
                        .equals(requiredProfile.scope().organizationId())
                && requiredConfiguration.agentProfileId().equals(requiredProfile.id())
                && requiredConfiguration.ownership().equals(requiredProfile.ownership())
                && requiredConfiguration.templateVersion()
                        .equals(SkillDistillerTemplate.VERSION)
                && requiredConfiguration.templateContentHash()
                        .equals(requiredTemplate.contentHash())
                && requiredConfiguration.personalModelBinding().isEmpty()
                && requiredConfiguration.teamModelBinding().isPresent()
                && requiredConfiguration.teamModelBinding().orElseThrow().executionScope()
                        == AgentExecutionScope.TEAM
                && requiredConfiguration.templateConfiguration().enabledTools().isEmpty()
                && requiredConfiguration.approvedSkillKeys().isEmpty()
                && requiredConfiguration.templateConfiguration().supplementalInstructions().isEmpty()
                && requiredTemplate.policy().structuredOutputSchemaHash()
                        .filter(OUTPUT_SCHEMA_HASH::equals).isPresent()
                && requiredConfiguration.templateConfiguration().structuredOutputSchemaHash()
                        .filter(OUTPUT_SCHEMA_HASH::equals).isPresent();
        boolean teamModel = requiredResolved.agentProfileId().equals(requiredProfile.id())
                && requiredResolved.agentProfileVersion() == requiredProfile.version()
                && requiredResolved.agentPrincipalId().equals(requiredProfile.agentPrincipalId())
                && requiredResolved.ownership().equals(requiredProfile.ownership())
                && requiredResolved.ownership().type() == AgentOwnershipType.TEAM
                && requiredResolved.ownership().teamId().equals(requiredProfile.scope().teamId())
                && requiredResolved.templateVersion().equals(SkillDistillerTemplate.VERSION)
                && requiredResolved.templateContentHash()
                        .equals(requiredTemplate.contentHash())
                && requiredResolved.configurationRevision()
                        .equals(requiredConfiguration.revision())
                && requiredResolved.configurationHash()
                        .equals(requiredConfiguration.configurationHash())
                && requiredResolved.executionScope() == AgentExecutionScope.TEAM
                && requiredResolved.primary().connectionOwner().type()
                        != ModelConnectionOwnerType.USER
                && requiredResolved.fallback()
                        .map(value -> value.connectionOwner().type() != ModelConnectionOwnerType.USER)
                        .orElse(true)
                && requiredResolved.structuredOutputSchemaHash()
                        .filter(OUTPUT_SCHEMA_HASH::equals).isPresent();
        if (!exactGraph || !teamModel) {
            throw new IllegalArgumentException(
                    "Skill Distiller Runtime requires the exact tool-less TEAM contract");
        }
    }
}
