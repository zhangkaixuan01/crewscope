package io.crewscope.domain.agent;

import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import java.util.Objects;
import java.util.Optional;

/** Immutable, hash-closed execution configuration produced after full model preflight. */
public record ResolvedAgentExecutionConfiguration(
        AgentProfileId agentProfileId,
        long agentProfileVersion,
        PrincipalId agentPrincipalId,
        AgentOwnership ownership,
        AgentTemplateVersion templateVersion,
        AgentTemplateHash templateContentHash,
        AgentConfigurationRevision configurationRevision,
        AgentConfigurationHash configurationHash,
        AgentExecutionScope executionScope,
        AgentModelBindingSource bindingSource,
        Optional<ResolvedAgentModelDefault> modelDefault,
        ResolvedModelSelection primary,
        Optional<ResolvedModelSelection> fallback,
        AgentTemplateHash promptHash,
        AgentTemplateHash toolHash,
        AgentTemplateHash skillHash,
        Optional<AgentTemplateHash> structuredOutputSchemaHash,
        AgentTemplateHash templatePolicyHash,
        PolicyPackReference configurationPolicyPack,
        AgentConfigurationHash resolutionHash) {

    public ResolvedAgentExecutionConfiguration {
        agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        if (agentProfileVersion < 0) {
            throw rejected(AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }
        agentPrincipalId = Objects.requireNonNull(agentPrincipalId, "agentPrincipalId");
        ownership = Objects.requireNonNull(ownership, "ownership");
        templateVersion = Objects.requireNonNull(templateVersion, "templateVersion");
        templateContentHash = Objects.requireNonNull(templateContentHash, "templateContentHash");
        configurationRevision = Objects.requireNonNull(
                configurationRevision, "configurationRevision");
        configurationHash = Objects.requireNonNull(configurationHash, "configurationHash");
        executionScope = Objects.requireNonNull(executionScope, "executionScope");
        bindingSource = Objects.requireNonNull(bindingSource, "bindingSource");
        modelDefault = Objects.requireNonNull(modelDefault, "modelDefault");
        primary = Objects.requireNonNull(primary, "primary");
        fallback = Objects.requireNonNull(fallback, "fallback");
        promptHash = Objects.requireNonNull(promptHash, "promptHash");
        toolHash = Objects.requireNonNull(toolHash, "toolHash");
        skillHash = Objects.requireNonNull(skillHash, "skillHash");
        structuredOutputSchemaHash = Objects.requireNonNull(
                structuredOutputSchemaHash, "structuredOutputSchemaHash");
        templatePolicyHash = Objects.requireNonNull(templatePolicyHash, "templatePolicyHash");
        configurationPolicyPack = Objects.requireNonNull(
                configurationPolicyPack, "configurationPolicyPack");
        boolean invalidFallbackRole = fallback.isPresent()
                && fallback.orElseThrow().role() != ResolvedModelRole.FALLBACK;
        boolean duplicateFallback = fallback.isPresent()
                && fallback.orElseThrow().connectionId().equals(primary.connectionId())
                && fallback.orElseThrow().catalogCoordinate().equals(primary.catalogCoordinate());
        boolean invalidDefaultSource = modelDefault.isPresent()
                && modelDefault.orElseThrow().source() != bindingSource;
        if (primary.role() != ResolvedModelRole.PRIMARY
                || invalidFallbackRole
                || duplicateFallback
                || (bindingSource == AgentModelBindingSource.DIRECT) != modelDefault.isEmpty()
                || invalidDefaultSource) {
            throw rejected(AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }
        AgentConfigurationHash expected = calculateHash(
                agentProfileId,
                agentProfileVersion,
                agentPrincipalId,
                ownership,
                templateVersion,
                templateContentHash,
                configurationRevision,
                configurationHash,
                executionScope,
                bindingSource,
                modelDefault,
                primary,
                fallback,
                promptHash,
                toolHash,
                skillHash,
                structuredOutputSchemaHash,
                templatePolicyHash,
                configurationPolicyPack);
        if (!expected.equals(Objects.requireNonNull(resolutionHash, "resolutionHash"))) {
            throw rejected(AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }
    }

    /** Closes Profile, Template, Configuration, binding source and independently resolved models. */
    public static ResolvedAgentExecutionConfiguration resolve(
            AgentProfile profile,
            AgentTemplateDefinition template,
            AgentConfigurationVersion configuration,
            AgentExecutionScope executionScope,
            AgentModelBindingSource bindingSource,
            Optional<AgentModelDefault> modelDefault,
            ResolvedModelSelection primary,
            Optional<ResolvedModelSelection> fallback) {
        AgentProfile requiredProfile = Objects.requireNonNull(profile, "profile");
        AgentTemplateDefinition requiredTemplate = Objects.requireNonNull(template, "template");
        AgentConfigurationVersion requiredConfiguration = Objects.requireNonNull(
                configuration, "configuration");
        AgentExecutionScope requiredScope = Objects.requireNonNull(
                executionScope, "executionScope");
        AgentModelBindingSource requiredSource = Objects.requireNonNull(
                bindingSource, "bindingSource");
        Optional<AgentModelDefault> requiredDefault = Objects.requireNonNull(
                modelDefault, "modelDefault");

        if (requiredProfile.status() != AgentProfileStatus.ACTIVE
                || requiredTemplate.status() != AgentTemplateStatus.ACTIVE) {
            throw rejected(AgentModelPreflightRejectionCode.AGENT_UNAVAILABLE);
        }
        if (!requiredProfile.id().equals(requiredConfiguration.agentProfileId())
                || requiredProfile.version() < 0
                || !requiredProfile.ownership().equals(requiredConfiguration.ownership())
                || !requiredProfile.templateVersion().equals(requiredTemplate.templateVersion())
                || !requiredConfiguration.templateVersion().equals(requiredTemplate.templateVersion())
                || !requiredConfiguration.templateContentHash().equals(requiredTemplate.contentHash())) {
            throw rejected(AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }
        try {
            requiredTemplate.requireExecutable(requiredProfile.ownership(), requiredScope);
        } catch (DomainValidationException unsupported) {
            throw rejected(AgentModelPreflightRejectionCode.EXECUTION_SCOPE_UNSUPPORTED);
        }
        Optional<ResolvedAgentModelDefault> capturedDefault = requiredDefault.map(value -> {
            if (requiredSource == AgentModelBindingSource.DIRECT
                    || value.executionScope() != requiredScope
                    || !value.templateVersion().equals(requiredTemplate.templateVersion())
                    || !value.templateContentHash().equals(requiredTemplate.contentHash())) {
                throw rejected(AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
            }
            return ResolvedAgentModelDefault.capture(requiredSource, value);
        });
        if ((requiredSource == AgentModelBindingSource.DIRECT) != capturedDefault.isEmpty()) {
            throw rejected(AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }

        AgentTemplateHash promptHash = calculatePromptHash(
                requiredTemplate.policy().systemPromptBaseline(),
                requiredConfiguration.templateConfiguration().supplementalInstructions());
        AgentTemplateHash toolHash = calculateToolHash(
                requiredConfiguration.templateConfiguration().enabledTools());
        AgentTemplateHash skillHash = calculateSkillHash(
                requiredConfiguration.approvedSkillKeys());

        AgentConfigurationHash hash = calculateHash(
                requiredProfile.id(),
                requiredProfile.version(),
                requiredProfile.agentPrincipalId(),
                requiredProfile.ownership(),
                requiredTemplate.templateVersion(),
                requiredTemplate.contentHash(),
                requiredConfiguration.revision(),
                requiredConfiguration.configurationHash(),
                requiredScope,
                requiredSource,
                capturedDefault,
                primary,
                fallback,
                promptHash,
                toolHash,
                skillHash,
                requiredConfiguration.templateConfiguration().structuredOutputSchemaHash(),
                requiredTemplate.policy().policyHash(),
                requiredConfiguration.policyPack());
        return new ResolvedAgentExecutionConfiguration(
                requiredProfile.id(),
                requiredProfile.version(),
                requiredProfile.agentPrincipalId(),
                requiredProfile.ownership(),
                requiredTemplate.templateVersion(),
                requiredTemplate.contentHash(),
                requiredConfiguration.revision(),
                requiredConfiguration.configurationHash(),
                requiredScope,
                requiredSource,
                capturedDefault,
                primary,
                fallback,
                promptHash,
                toolHash,
                skillHash,
                requiredConfiguration.templateConfiguration().structuredOutputSchemaHash(),
                requiredTemplate.policy().policyHash(),
                requiredConfiguration.policyPack(),
                hash);
    }

    private static AgentTemplateHash calculatePromptHash(
            String systemPromptBaseline, Optional<String> supplementalInstructions) {
        StringBuilder canonical = new StringBuilder("resolved-agent-prompt-v2");
        AgentTemplateHash.append(
                canonical, Objects.requireNonNull(systemPromptBaseline, "systemPromptBaseline"));
        Optional<String> supplemental = Objects.requireNonNull(
                supplementalInstructions, "supplementalInstructions");
        AgentTemplateHash.append(
                canonical, supplemental.isPresent() ? "supplemental:present" : "supplemental:absent");
        supplemental.ifPresent(value -> AgentTemplateHash.append(canonical, value));
        return AgentTemplateHash.sha256(canonical.toString());
    }

    private static AgentTemplateHash calculateToolHash(java.util.Set<AgentToolKey> tools) {
        StringBuilder canonical = new StringBuilder("resolved-agent-tools-v2");
        Objects.requireNonNull(tools, "tools").stream()
                .map(Object::toString)
                .sorted()
                .forEach(value -> AgentTemplateHash.append(canonical, value));
        return AgentTemplateHash.sha256(canonical.toString());
    }

    private static AgentTemplateHash calculateSkillHash(java.util.Set<String> skillKeys) {
        StringBuilder canonical = new StringBuilder("resolved-agent-skills-v2");
        Objects.requireNonNull(skillKeys, "skillKeys").stream()
                .sorted()
                .forEach(value -> AgentTemplateHash.append(canonical, value));
        return AgentTemplateHash.sha256(canonical.toString());
    }

    private static AgentConfigurationHash calculateHash(
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            PrincipalId agentPrincipalId,
            AgentOwnership ownership,
            AgentTemplateVersion templateVersion,
            AgentTemplateHash templateContentHash,
            AgentConfigurationRevision configurationRevision,
            AgentConfigurationHash configurationHash,
            AgentExecutionScope executionScope,
            AgentModelBindingSource bindingSource,
            Optional<ResolvedAgentModelDefault> modelDefault,
            ResolvedModelSelection primary,
            Optional<ResolvedModelSelection> fallback,
            AgentTemplateHash promptHash,
            AgentTemplateHash toolHash,
            AgentTemplateHash skillHash,
            Optional<AgentTemplateHash> structuredOutputSchemaHash,
            AgentTemplateHash templatePolicyHash,
            PolicyPackReference configurationPolicyPack) {
        StringBuilder canonical = new StringBuilder("resolved-agent-execution-configuration-v1");
        AgentConfigurationHash.append(canonical, agentProfileId.toString());
        AgentConfigurationHash.append(canonical, Long.toString(agentProfileVersion));
        AgentConfigurationHash.append(canonical, agentPrincipalId.toString());
        AgentConfigurationHash.append(canonical, ownership.type().name());
        AgentConfigurationHash.append(canonical, ownership.organizationId().toString());
        AgentConfigurationHash.append(
                canonical, ownership.teamId().map(Object::toString).orElse("team:none"));
        AgentConfigurationHash.append(
                canonical,
                ownership.ownerMemberId().map(Object::toString).orElse("member:none"));
        AgentConfigurationHash.append(canonical, templateVersion.toString());
        AgentConfigurationHash.append(canonical, templateContentHash.toString());
        AgentConfigurationHash.append(canonical, configurationRevision.toString());
        AgentConfigurationHash.append(canonical, configurationHash.toString());
        AgentConfigurationHash.append(canonical, executionScope.name());
        AgentConfigurationHash.append(canonical, bindingSource.name());
        modelDefault.ifPresentOrElse(
                value -> {
                    AgentConfigurationHash.append(canonical, value.scope().organizationId().toString());
                    AgentConfigurationHash.append(
                            canonical,
                            value.scope().teamId().map(Object::toString).orElse("team:none"));
                    AgentConfigurationHash.append(canonical, value.revision().toString());
                    AgentConfigurationHash.append(canonical, value.contentHash().toString());
                    AgentConfigurationHash.append(canonical, value.policyPack().id().toString());
                    AgentConfigurationHash.append(
                            canonical, Long.toString(value.policyPack().version()));
                },
                () -> AgentConfigurationHash.append(canonical, "default:none"));
        AgentConfigurationHash.append(canonical, primary.resolutionHash().toString());
        AgentConfigurationHash.append(
                canonical,
                fallback.map(value -> value.resolutionHash().toString()).orElse("fallback:none"));
        AgentConfigurationHash.append(canonical, promptHash.toString());
        AgentConfigurationHash.append(canonical, toolHash.toString());
        AgentConfigurationHash.append(canonical, skillHash.toString());
        AgentConfigurationHash.append(
                canonical,
                structuredOutputSchemaHash.map(Object::toString).orElse("schema:none"));
        AgentConfigurationHash.append(canonical, templatePolicyHash.toString());
        AgentConfigurationHash.append(canonical, configurationPolicyPack.id().toString());
        AgentConfigurationHash.append(
                canonical, Long.toString(configurationPolicyPack.version()));
        return AgentConfigurationHash.sha256(canonical.toString());
    }

    private static AgentModelPreflightException rejected(
            AgentModelPreflightRejectionCode reason) {
        return new AgentModelPreflightException(reason);
    }
}
