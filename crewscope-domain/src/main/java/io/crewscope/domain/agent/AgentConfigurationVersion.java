package io.crewscope.domain.agent;

import io.crewscope.domain.model.ModelConnectionOwnerType;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Append-only, template-backed runtime configuration for one stable AgentProfile. */
public final class AgentConfigurationVersion {

    private final OrganizationId organizationId;
    private final AgentProfileId agentProfileId;
    private final AgentOwnership ownership;
    private final Optional<PrincipalId> ownerUserPrincipalId;
    private final AgentTemplateVersion templateVersion;
    private final AgentTemplateHash templateContentHash;
    private final AgentConfigurationRevision revision;
    private final Optional<AgentConfigurationRevision> previousRevision;
    private final Optional<AgentExecutionModelBinding> personalModelBinding;
    private final Optional<AgentExecutionModelBinding> teamModelBinding;
    private final AgentTemplateMemberConfiguration templateConfiguration;
    private final Set<String> approvedSkillKeys;
    private final Optional<AgentMemoryPolicyReference> memoryPolicy;
    private final Optional<AgentBudgetPolicyReference> budgetPolicy;
    private final PolicyPackReference policyPack;
    private final SafeModelGenerateOptions generateOptions;
    private final AgentConfigurationHash configurationHash;
    private final AuditMetadata audit;

    private AgentConfigurationVersion(
            AgentProfile profile,
            AgentTemplateDefinition template,
            Optional<PrincipalId> ownerUserPrincipalId,
            AgentConfigurationRevision revision,
            Optional<AgentConfigurationRevision> previousRevision,
            Optional<AgentExecutionModelBinding> personalModelBinding,
            Optional<AgentExecutionModelBinding> teamModelBinding,
            AgentTemplateMemberConfiguration templateConfiguration,
            Set<String> approvedSkillKeys,
            Optional<AgentMemoryPolicyReference> memoryPolicy,
            Optional<AgentBudgetPolicyReference> budgetPolicy,
            PolicyPackReference policyPack,
            SafeModelGenerateOptions generateOptions,
            AuditMetadata audit,
            AgentConfigurationHash expectedConfigurationHash,
            boolean requireActiveFacts) {
        AgentProfile requiredProfile = Objects.requireNonNull(profile, "profile");
        AgentTemplateDefinition requiredTemplate = Objects.requireNonNull(template, "template");
        requireProfileTemplateFacts(requiredProfile, requiredTemplate, requireActiveFacts);
        this.organizationId = requiredProfile.scope().organizationId();
        this.agentProfileId = requiredProfile.id();
        this.ownership = requiredProfile.ownership();
        this.ownerUserPrincipalId = requireOwnerUserPrincipal(
                this.ownership, ownerUserPrincipalId);
        this.templateVersion = requiredTemplate.templateVersion();
        this.templateContentHash = requiredTemplate.contentHash();
        this.revision = Objects.requireNonNull(revision, "revision");
        this.previousRevision = requirePreviousRevision(this.revision, previousRevision);
        this.personalModelBinding = Objects.requireNonNull(
                personalModelBinding, "personalModelBinding");
        this.teamModelBinding = Objects.requireNonNull(teamModelBinding, "teamModelBinding");
        requireBindingShape(requiredProfile, requiredTemplate);
        this.templateConfiguration = requireTemplateConfiguration(
                requiredTemplate, templateConfiguration);
        this.approvedSkillKeys = requireApprovedSkills(requiredTemplate, approvedSkillKeys);
        this.memoryPolicy = Objects.requireNonNull(memoryPolicy, "memoryPolicy");
        this.budgetPolicy = Objects.requireNonNull(budgetPolicy, "budgetPolicy");
        this.policyPack = Objects.requireNonNull(policyPack, "policyPack");
        this.generateOptions = Objects.requireNonNull(generateOptions, "generateOptions");
        requireConfigurableSlots(requiredTemplate);
        requireDirectBindingScope(this.personalModelBinding);
        requireDirectBindingScope(this.teamModelBinding);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.configurationHash = calculateConfigurationHash();
        if (expectedConfigurationHash != null
                && !expectedConfigurationHash.equals(this.configurationHash)) {
            throw new DomainValidationException(
                    "agentConfiguration.configurationHash",
                    "must match the canonical Agent configuration");
        }
    }

    /** Creates revision one after the template has validated the member-controlled prompt shape. */
    public static AgentConfigurationVersion createInitial(
            AgentProfile profile,
            AgentTemplateDefinition template,
            Optional<PrincipalId> ownerUserPrincipalId,
            Optional<AgentExecutionModelBinding> personalModelBinding,
            Optional<AgentExecutionModelBinding> teamModelBinding,
            Optional<String> supplementalInstructions,
            Set<String> approvedSkillKeys,
            Optional<AgentMemoryPolicyReference> memoryPolicy,
            Optional<AgentBudgetPolicyReference> budgetPolicy,
            PolicyPackReference policyPack,
            SafeModelGenerateOptions generateOptions,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        AgentTemplateDefinition requiredTemplate = Objects.requireNonNull(template, "template");
        AgentTemplateMemberConfiguration configuration = requiredTemplate.policy()
                .resolveMemberConfiguration(
                        supplementalInstructions,
                        requiredTemplate.policy().allowedTools(),
                        requiredTemplate.policy().structuredOutputSchema());
        return new AgentConfigurationVersion(
                profile,
                requiredTemplate,
                ownerUserPrincipalId,
                new AgentConfigurationRevision(1),
                Optional.empty(),
                personalModelBinding,
                teamModelBinding,
                configuration,
                approvedSkillKeys,
                memoryPolicy,
                budgetPolicy,
                policyPack,
                generateOptions,
                AuditMetadata.createdBy(actor, occurredAt),
                null,
                true);
    }

    /** Appends the next revision and leaves this historical version unchanged. */
    public AgentConfigurationVersion appendNext(
            AgentProfile profile,
            AgentTemplateDefinition template,
            Optional<AgentExecutionModelBinding> nextPersonalModelBinding,
            Optional<AgentExecutionModelBinding> nextTeamModelBinding,
            Optional<String> nextSupplementalInstructions,
            Set<String> nextApprovedSkillKeys,
            Optional<AgentMemoryPolicyReference> nextMemoryPolicy,
            Optional<AgentBudgetPolicyReference> nextBudgetPolicy,
            PolicyPackReference nextPolicyPack,
            SafeModelGenerateOptions nextGenerateOptions,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        requireSameProfileAndTemplate(profile, template);
        AgentTemplateMemberConfiguration configuration = template.policy()
                .resolveMemberConfiguration(
                        nextSupplementalInstructions,
                        template.policy().allowedTools(),
                        template.policy().structuredOutputSchema());
        return new AgentConfigurationVersion(
                profile,
                template,
                ownerUserPrincipalId,
                revision.next(),
                Optional.of(revision),
                nextPersonalModelBinding,
                nextTeamModelBinding,
                configuration,
                nextApprovedSkillKeys,
                nextMemoryPolicy,
                nextBudgetPolicy,
                nextPolicyPack,
                nextGenerateOptions,
                AuditMetadata.createdBy(actor, occurredAt),
                null,
                true);
    }

    /** Reconstitutes a historical revision and verifies its exact canonical hash. */
    public static AgentConfigurationVersion reconstitute(
            AgentProfile profile,
            AgentTemplateDefinition template,
            Optional<PrincipalId> ownerUserPrincipalId,
            AgentConfigurationRevision revision,
            Optional<AgentConfigurationRevision> previousRevision,
            Optional<AgentExecutionModelBinding> personalModelBinding,
            Optional<AgentExecutionModelBinding> teamModelBinding,
            AgentTemplateMemberConfiguration templateConfiguration,
            Set<String> approvedSkillKeys,
            Optional<AgentMemoryPolicyReference> memoryPolicy,
            Optional<AgentBudgetPolicyReference> budgetPolicy,
            PolicyPackReference policyPack,
            SafeModelGenerateOptions generateOptions,
            AgentConfigurationHash configurationHash,
            AuditMetadata audit) {
        return new AgentConfigurationVersion(
                profile,
                template,
                ownerUserPrincipalId,
                revision,
                previousRevision,
                personalModelBinding,
                teamModelBinding,
                templateConfiguration,
                approvedSkillKeys,
                memoryPolicy,
                budgetPolicy,
                policyPack,
                generateOptions,
                audit,
                Objects.requireNonNull(configurationHash, "configurationHash"),
                false);
    }

    private void requireSameProfileAndTemplate(
            AgentProfile profile, AgentTemplateDefinition template) {
        AgentProfile requiredProfile = Objects.requireNonNull(profile, "profile");
        AgentTemplateDefinition requiredTemplate = Objects.requireNonNull(template, "template");
        if (!agentProfileId.equals(requiredProfile.id())
                || !ownership.equals(requiredProfile.ownership())
                || !templateVersion.equals(requiredTemplate.templateVersion())
                || !templateContentHash.equals(requiredTemplate.contentHash())) {
            throw new DomainValidationException(
                    "agentConfiguration.agentProfileId",
                    "must preserve the exact Profile, Ownership and Template coordinates");
        }
    }

    private void requireBindingShape(
            AgentProfile profile, AgentTemplateDefinition template) {
        boolean allowsPersonal = template.allowedExecutionScopes()
                .contains(AgentExecutionScope.PERSONAL);
        boolean allowsTeam = template.allowedExecutionScopes().contains(AgentExecutionScope.TEAM);
        if (allowsPersonal != personalModelBinding.isPresent()) {
            throw new DomainValidationException(
                    "agentConfiguration.personalModelBinding",
                    "must be present exactly when PERSONAL execution is allowed");
        }
        personalModelBinding.ifPresent(binding -> {
            if (binding.executionScope() != AgentExecutionScope.PERSONAL
                    || binding.kind() != AgentModelBindingKind.DIRECT) {
                throw new DomainValidationException(
                        "agentConfiguration.personalModelBinding",
                        "must be a DIRECT PERSONAL binding");
            }
            template.requireExecutable(ownership, AgentExecutionScope.PERSONAL);
        });
        if (allowsTeam != teamModelBinding.isPresent()) {
            throw new DomainValidationException(
                    "agentConfiguration.teamModelBinding",
                    "must be present exactly when TEAM execution is allowed");
        }
        teamModelBinding.ifPresent(binding -> {
            if (binding.executionScope() != AgentExecutionScope.TEAM) {
                throw new DomainValidationException(
                        "agentConfiguration.teamModelBinding", "must use TEAM execution scope");
            }
            if (profile.runtimeRole() == AgentRuntimeRole.PERSONAL_ASSISTANT) {
                if (binding.kind() != AgentModelBindingKind.ORCHESTRATION_ONLY) {
                    throw new DomainValidationException(
                            "agentConfiguration.teamModelBinding",
                            "a Personal Assistant must remain orchestration-only for TEAM scope");
                }
            } else if (binding.kind() == AgentModelBindingKind.ORCHESTRATION_ONLY) {
                throw new DomainValidationException(
                        "agentConfiguration.teamModelBinding",
                        "an execution Agent cannot use ORCHESTRATION_ONLY");
            }
            template.requireExecutable(ownership, AgentExecutionScope.TEAM);
        });
    }

    private void requireDirectBindingScope(Optional<AgentExecutionModelBinding> binding) {
        binding.filter(value -> value.kind() == AgentModelBindingKind.DIRECT)
                .flatMap(AgentExecutionModelBinding::directBinding)
                .ifPresent(direct -> {
                    requireSelectionScope(binding.orElseThrow().executionScope(), direct.primary());
                    direct.fallback().ifPresent(selection ->
                            requireSelectionScope(binding.orElseThrow().executionScope(), selection));
                });
    }

    private void requireSelectionScope(
            AgentExecutionScope executionScope, AgentModelSelection selection) {
        if (!organizationId.equals(selection.organizationId())) {
            throw new DomainValidationException(
                    "agentConfiguration.modelBinding.organizationId",
                    "must match the Agent Organization");
        }
        ModelConnectionOwnerType connectionOwnerType = selection.connectionOwner().type();
        switch (ownership.type()) {
            case USER -> {
                if (executionScope == AgentExecutionScope.TEAM
                        && connectionOwnerType == ModelConnectionOwnerType.USER) {
                    throw new DomainValidationException(
                            "agentConfiguration.teamModelBinding",
                            "must not use a USER Connection");
                }
                if (connectionOwnerType == ModelConnectionOwnerType.USER
                        && selection.connectionOwner().userPrincipalId()
                                .filter(ownerUserPrincipalId.orElseThrow()::equals)
                                .isEmpty()) {
                    throw new DomainValidationException(
                            "agentConfiguration.personalModelBinding",
                            "a USER Connection must belong to the Agent owner");
                }
                requireSameTeamWhenTeamOwned(selection);
            }
            case TEAM -> {
                if (connectionOwnerType == ModelConnectionOwnerType.USER) {
                    throw new DomainValidationException(
                            "agentConfiguration.modelBinding",
                            "a TEAM-owned Agent must not use a USER Connection");
                }
                requireSameTeamWhenTeamOwned(selection);
            }
            case ORGANIZATION -> {
                if (connectionOwnerType != ModelConnectionOwnerType.ORGANIZATION) {
                    throw new DomainValidationException(
                            "agentConfiguration.modelBinding",
                            "an ORGANIZATION-owned Agent must use an Organization Connection");
                }
            }
        }
    }

    private void requireSameTeamWhenTeamOwned(AgentModelSelection selection) {
        if (selection.connectionOwner().type() == ModelConnectionOwnerType.TEAM
                && selection.connectionOwner().teamId()
                        .filter(ownership.teamId().orElseThrow()::equals)
                        .isEmpty()) {
            throw new DomainValidationException(
                    "agentConfiguration.modelBinding",
                    "a Team Connection must match the Agent Team");
        }
    }

    private void requireConfigurableSlots(AgentTemplateDefinition template) {
        template.policy().requireConfigurable(AgentConfigurableSlot.MODEL_BINDING);
        if (!approvedSkillKeys.isEmpty()) {
            template.policy().requireConfigurable(AgentConfigurableSlot.APPROVED_SKILLS);
        }
        if (memoryPolicy.isPresent()) {
            template.policy().requireConfigurable(AgentConfigurableSlot.KNOWLEDGE_SCOPE);
        }
        if (budgetPolicy.isPresent()) {
            template.policy().requireConfigurable(AgentConfigurableSlot.BUDGET);
        }
        if (!SafeModelGenerateOptions.defaults().equals(generateOptions)) {
            template.policy().requireConfigurable(AgentConfigurableSlot.OUTPUT_PREFERENCE);
        }
    }

    private AgentConfigurationHash calculateConfigurationHash() {
        StringBuilder canonical = new StringBuilder("agent-configuration-version-v1");
        AgentConfigurationHash.append(canonical, organizationId.toString());
        AgentConfigurationHash.append(canonical, agentProfileId.toString());
        AgentConfigurationHash.append(canonical, ownership.type().name());
        AgentConfigurationHash.append(
                canonical, ownership.teamId().map(Object::toString).orElse("team:none"));
        AgentConfigurationHash.append(
                canonical,
                ownership.ownerMemberId().map(Object::toString).orElse("member:none"));
        AgentConfigurationHash.append(
                canonical,
                ownerUserPrincipalId.map(Object::toString).orElse("ownerPrincipal:none"));
        AgentConfigurationHash.append(canonical, templateVersion.toString());
        AgentConfigurationHash.append(canonical, templateContentHash.toString());
        AgentConfigurationHash.append(canonical, Long.toString(revision.value()));
        AgentConfigurationHash.append(
                canonical,
                previousRevision.map(value -> Long.toString(value.value()))
                        .orElse("previous:none"));
        appendBinding(canonical, personalModelBinding, "personal");
        appendBinding(canonical, teamModelBinding, "team");
        AgentConfigurationHash.append(
                canonical,
                templateConfiguration.supplementalInstructions().orElse("instructions:none"));
        templateConfiguration.enabledTools().stream()
                .sorted(Comparator.naturalOrder())
                .forEach(tool -> AgentConfigurationHash.append(canonical, "tool:" + tool));
        AgentConfigurationHash.append(
                canonical,
                templateConfiguration.structuredOutputSchemaHash()
                        .map(Object::toString)
                        .orElse("schema:none"));
        approvedSkillKeys.stream()
                .sorted()
                .forEach(skill -> AgentConfigurationHash.append(canonical, "skill:" + skill));
        AgentConfigurationHash.append(
                canonical,
                memoryPolicy.map(reference ->
                                reference.policyId() + "@" + reference.version())
                        .orElse("memory:none"));
        AgentConfigurationHash.append(
                canonical,
                budgetPolicy.map(reference ->
                                reference.policyId() + "@" + reference.version())
                        .orElse("budget:none"));
        AgentConfigurationHash.append(canonical, policyPack.id().toString());
        AgentConfigurationHash.append(canonical, Long.toString(policyPack.version()));
        generateOptions.appendCanonical(canonical);
        return AgentConfigurationHash.sha256(canonical.toString());
    }

    private static void appendBinding(
            StringBuilder canonical,
            Optional<AgentExecutionModelBinding> binding,
            String label) {
        AgentConfigurationHash.append(canonical, label);
        binding.ifPresentOrElse(
                value -> value.appendCanonical(canonical),
                () -> AgentConfigurationHash.append(canonical, label + ":none"));
    }

    private static void requireProfileTemplateFacts(
            AgentProfile profile,
            AgentTemplateDefinition template,
            boolean requireActiveFacts) {
        if (!profile.templateVersion().equals(template.templateVersion())
                || !profile.scope()
                        .organizationId()
                        .equals(template.publisherScope().organizationId())) {
            throw new DomainValidationException(
                    "agentConfiguration.templateVersion",
                    "must match the Profile and template Organization");
        }
        if (requireActiveFacts
                && (profile.status() != AgentProfileStatus.ACTIVE
                        || template.status() != AgentTemplateStatus.ACTIVE)) {
            throw new DomainValidationException(
                    "agentConfiguration.status",
                    "Profile and Agent template must both be ACTIVE");
        }
    }

    private static Optional<PrincipalId> requireOwnerUserPrincipal(
            AgentOwnership ownership, Optional<PrincipalId> ownerUserPrincipalId) {
        Optional<PrincipalId> required = Objects.requireNonNull(
                ownerUserPrincipalId, "ownerUserPrincipalId");
        if ((ownership.type() == AgentOwnershipType.USER) != required.isPresent()) {
            throw new DomainValidationException(
                    "agentConfiguration.ownerUserPrincipalId",
                    "must be present exactly for USER-owned Agents");
        }
        return required;
    }

    private static Optional<AgentConfigurationRevision> requirePreviousRevision(
            AgentConfigurationRevision current,
            Optional<AgentConfigurationRevision> previousRevision) {
        Optional<AgentConfigurationRevision> required = Objects.requireNonNull(
                previousRevision, "previousRevision");
        if (current.value() == 1 && required.isPresent()) {
            throw new DomainValidationException(
                    "agentConfiguration.previousRevision", "must be empty for revision one");
        }
        if (current.value() > 1) {
            AgentConfigurationRevision previous = required.orElseThrow(() ->
                    new DomainValidationException(
                            "agentConfiguration.previousRevision",
                            "is required after revision one"));
            if (previous.value() != current.value() - 1) {
                throw new DomainValidationException(
                        "agentConfiguration.previousRevision",
                        "must reference the immediately preceding revision");
            }
        }
        return required;
    }

    private static Set<String> requireApprovedSkills(
            AgentTemplateDefinition template, Set<String> approvedSkillKeys) {
        Set<String> required = Set.copyOf(
                Objects.requireNonNull(approvedSkillKeys, "approvedSkillKeys"));
        if (!template.policy().approvedSkillKeys().containsAll(required)) {
            throw new DomainValidationException(
                    "agentConfiguration.approvedSkillKeys",
                    "must be a subset of the Agent template approved Skills");
        }
        return required;
    }

    private static AgentTemplateMemberConfiguration requireTemplateConfiguration(
            AgentTemplateDefinition template,
            AgentTemplateMemberConfiguration templateConfiguration) {
        AgentTemplateMemberConfiguration required = Objects.requireNonNull(
                templateConfiguration, "templateConfiguration");
        AgentTemplateMemberConfiguration verified = template.policy().resolveMemberConfiguration(
                required.supplementalInstructions(),
                required.enabledTools(),
                template.policy().structuredOutputSchema());
        if (!verified.equals(required)) {
            throw new DomainValidationException(
                    "agentConfiguration.templateConfiguration",
                    "must match the exact template Prompt, Tool and Schema boundary");
        }
        return required;
    }

    public OrganizationId organizationId() { return organizationId; }

    public AgentProfileId agentProfileId() { return agentProfileId; }

    public AgentOwnership ownership() { return ownership; }

    public Optional<PrincipalId> ownerUserPrincipalId() { return ownerUserPrincipalId; }

    public AgentTemplateVersion templateVersion() { return templateVersion; }

    public AgentTemplateHash templateContentHash() { return templateContentHash; }

    public AgentConfigurationRevision revision() { return revision; }

    public Optional<AgentConfigurationRevision> previousRevision() { return previousRevision; }

    public Optional<AgentExecutionModelBinding> personalModelBinding() {
        return personalModelBinding;
    }

    public Optional<AgentExecutionModelBinding> teamModelBinding() { return teamModelBinding; }

    public AgentTemplateMemberConfiguration templateConfiguration() {
        return templateConfiguration;
    }

    public Set<String> approvedSkillKeys() { return approvedSkillKeys; }

    public Optional<AgentMemoryPolicyReference> memoryPolicy() { return memoryPolicy; }

    public Optional<AgentBudgetPolicyReference> budgetPolicy() { return budgetPolicy; }

    public PolicyPackReference policyPack() { return policyPack; }

    public SafeModelGenerateOptions generateOptions() { return generateOptions; }

    public AgentConfigurationHash configurationHash() { return configurationHash; }

    public AuditMetadata audit() { return audit; }
}
