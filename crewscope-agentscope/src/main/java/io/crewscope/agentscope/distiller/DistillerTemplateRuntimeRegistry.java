package io.crewscope.agentscope.distiller;

import io.crewscope.agentscope.template.AgentTemplateRuntimeDefinition;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerTemplate;
import io.crewscope.domain.model.ModelConnectionOwnerType;
import io.crewscope.domain.skill.distiller.SkillDistillerTemplate;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileStatus;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Fail-closed registry for the single built-in Distiller runtime contract, shared by the
 * Knowledge and Skill distillers (M13-C02): the exact TEAM model graph, zero Tools and the
 * kind's frozen distillation output schema — anything else must never reach a credential
 * handle. The per-kind contract (label, schema, profile/definition requirement, template
 * version) is supplied by the {@link #knowledge()}/{@link #skill()} factories.
 */
public final class DistillerTemplateRuntimeRegistry {

    private final String label;
    private final AgentTemplateHash outputSchemaHash;
    private final Function<AgentProfile, AgentProfile> profileRequirement;
    private final Function<AgentTemplateDefinition, AgentTemplateDefinition> definitionRequirement;
    private final AgentTemplateVersion templateVersion;

    public static DistillerTemplateRuntimeRegistry knowledge() {
        return new DistillerTemplateRuntimeRegistry(
                "Knowledge Distiller",
                KnowledgeDistillerTemplate::outputSchema,
                KnowledgeDistillerTemplate::requireProfile,
                KnowledgeDistillerTemplate::requireDefinition,
                KnowledgeDistillerTemplate.VERSION);
    }

    public static DistillerTemplateRuntimeRegistry skill() {
        return new DistillerTemplateRuntimeRegistry(
                "Skill Distiller",
                SkillDistillerTemplate::outputSchema,
                SkillDistillerTemplate::requireProfile,
                SkillDistillerTemplate::requireDefinition,
                SkillDistillerTemplate.VERSION);
    }

    private DistillerTemplateRuntimeRegistry(
            String label,
            Supplier<String> outputSchema,
            Function<AgentProfile, AgentProfile> profileRequirement,
            Function<AgentTemplateDefinition, AgentTemplateDefinition> definitionRequirement,
            AgentTemplateVersion templateVersion) {
        this.label = Objects.requireNonNull(label, "label");
        this.outputSchemaHash = AgentTemplateHash.sha256(
                Objects.requireNonNull(outputSchema, "outputSchema").get());
        this.profileRequirement = Objects.requireNonNull(profileRequirement, "profileRequirement");
        this.definitionRequirement =
                Objects.requireNonNull(definitionRequirement, "definitionRequirement");
        this.templateVersion = Objects.requireNonNull(templateVersion, "templateVersion");
    }

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
                    label + " Runtime requires the exact tool-less TEAM contract");
        }
        return required;
    }

    /** Validates all non-secret coordinates before the model factory opens a credential handle. */
    public void requireCoordinates(
            AgentProfile profile,
            AgentTemplateDefinition template,
            AgentConfigurationVersion configuration,
            ResolvedAgentExecutionConfiguration resolved) {
        AgentProfile requiredProfile = profileRequirement.apply(profile);
        AgentTemplateDefinition requiredTemplate = definitionRequirement.apply(template);
        AgentConfigurationVersion requiredConfiguration = Objects.requireNonNull(
                configuration, "configuration");
        ResolvedAgentExecutionConfiguration requiredResolved = Objects.requireNonNull(
                resolved, "resolved");
        boolean exactGraph = requiredProfile.status() == AgentProfileStatus.ACTIVE
                && requiredConfiguration.organizationId()
                        .equals(requiredProfile.scope().organizationId())
                && requiredConfiguration.agentProfileId().equals(requiredProfile.id())
                && requiredConfiguration.ownership().equals(requiredProfile.ownership())
                && requiredConfiguration.templateVersion().equals(templateVersion)
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
                        .filter(outputSchemaHash::equals).isPresent()
                && requiredConfiguration.templateConfiguration().structuredOutputSchemaHash()
                        .filter(outputSchemaHash::equals).isPresent();
        boolean teamModel = requiredResolved.agentProfileId().equals(requiredProfile.id())
                && requiredResolved.agentProfileVersion() == requiredProfile.version()
                && requiredResolved.agentPrincipalId().equals(requiredProfile.agentPrincipalId())
                && requiredResolved.ownership().equals(requiredProfile.ownership())
                && requiredResolved.ownership().type() == AgentOwnershipType.TEAM
                && requiredResolved.ownership().teamId().equals(requiredProfile.scope().teamId())
                && requiredResolved.templateVersion().equals(templateVersion)
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
                        .filter(outputSchemaHash::equals).isPresent();
        if (!exactGraph || !teamModel) {
            throw new IllegalArgumentException(
                    label + " Runtime requires the exact tool-less TEAM contract");
        }
    }
}
