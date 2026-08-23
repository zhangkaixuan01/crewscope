package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.Optional;

/** One PERSONAL or TEAM binding whose resolution mode is explicit and fail-closed. */
public record AgentExecutionModelBinding(
        AgentExecutionScope executionScope,
        AgentModelBindingKind kind,
        Optional<AgentDirectModelBinding> directBinding) {

    public AgentExecutionModelBinding {
        executionScope = Objects.requireNonNull(executionScope, "executionScope");
        kind = Objects.requireNonNull(kind, "kind");
        directBinding = Objects.requireNonNull(directBinding, "directBinding");
        boolean valid = switch (kind) {
            case DIRECT -> directBinding.isPresent();
            case INHERIT_TEAM_DEFAULT, ORCHESTRATION_ONLY -> directBinding.isEmpty()
                    && executionScope == AgentExecutionScope.TEAM;
        };
        if (!valid) {
            throw new DomainValidationException(
                    "agentConfiguration.modelBinding",
                    "has an invalid execution scope and resolution mode shape");
        }
    }

    public static AgentExecutionModelBinding direct(
            AgentExecutionScope executionScope, AgentDirectModelBinding binding) {
        return new AgentExecutionModelBinding(
                executionScope,
                AgentModelBindingKind.DIRECT,
                Optional.of(Objects.requireNonNull(binding, "binding")));
    }

    public static AgentExecutionModelBinding inheritTeamDefault() {
        return new AgentExecutionModelBinding(
                AgentExecutionScope.TEAM,
                AgentModelBindingKind.INHERIT_TEAM_DEFAULT,
                Optional.empty());
    }

    public static AgentExecutionModelBinding orchestrationOnly() {
        return new AgentExecutionModelBinding(
                AgentExecutionScope.TEAM,
                AgentModelBindingKind.ORCHESTRATION_ONLY,
                Optional.empty());
    }

    void appendCanonical(StringBuilder target) {
        AgentConfigurationHash.append(target, executionScope.name());
        AgentConfigurationHash.append(target, kind.name());
        directBinding.ifPresentOrElse(
                binding -> binding.appendCanonical(target),
                () -> AgentConfigurationHash.append(target, "direct:none"));
    }
}
