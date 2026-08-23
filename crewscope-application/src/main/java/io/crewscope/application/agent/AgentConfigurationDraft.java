package io.crewscope.application.agent;

import io.crewscope.domain.agent.AgentBudgetPolicyReference;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import io.crewscope.domain.agent.AgentModelBindingKind;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Compile-time client field whitelist for an Agent configuration write request. */
public record AgentConfigurationDraft(
        Optional<AgentModelBindingDraft> personalModelBinding,
        Optional<AgentModelBindingDraft> teamModelBinding,
        Optional<String> supplementalInstructions,
        Set<String> approvedSkillKeys,
        Optional<AgentMemoryPolicyReference> memoryPolicy,
        Optional<AgentBudgetPolicyReference> budgetPolicy,
        SafeModelGenerateOptions generateOptions) {

    public AgentConfigurationDraft {
        personalModelBinding = Objects.requireNonNull(
                personalModelBinding, "personalModelBinding");
        teamModelBinding = Objects.requireNonNull(teamModelBinding, "teamModelBinding");
        supplementalInstructions = Objects.requireNonNull(
                        supplementalInstructions, "supplementalInstructions")
                .map(String::strip)
                .filter(value -> !value.isEmpty());
        approvedSkillKeys = Set.copyOf(
                Objects.requireNonNull(approvedSkillKeys, "approvedSkillKeys"));
        memoryPolicy = Objects.requireNonNull(memoryPolicy, "memoryPolicy");
        budgetPolicy = Objects.requireNonNull(budgetPolicy, "budgetPolicy");
        generateOptions = Objects.requireNonNull(generateOptions, "generateOptions");
        personalModelBinding.ifPresent(binding -> {
            if (binding.kind() != AgentModelBindingKind.DIRECT) {
                throw new DomainValidationException(
                        "agentConfigurationDraft.personalModelBinding",
                        "must be DIRECT; defaults are resolved by the server");
            }
        });
    }
}
