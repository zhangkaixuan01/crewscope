package io.crewscope.agentscope.model;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.crewscope.domain.agent.AgentReasoningMode;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import java.time.Duration;
import java.util.Objects;

/** Maps CrewScope's whitelist without exposing AgentScope connection override fields. */
public final class SafeAgentScopeGenerateOptionsMapper {

    private SafeAgentScopeGenerateOptionsMapper() {}

    public static GenerateOptions map(
            SafeModelGenerateOptions source,
            Duration timeout,
            Duration initialBackoff,
            Duration maximumBackoff) {
        SafeModelGenerateOptions safe = Objects.requireNonNull(source, "source");
        Duration safeInitialBackoff = requirePositive(initialBackoff, "initialBackoff");
        Duration safeMaximumBackoff = requirePositive(maximumBackoff, "maximumBackoff");
        if (safeMaximumBackoff.compareTo(safeInitialBackoff) < 0) {
            throw new IllegalArgumentException(
                    "maximumBackoff must be greater than or equal to initialBackoff");
        }
        GenerateOptions.Builder target = GenerateOptions.builder()
                .cacheControl(safe.cacheEnabled())
                .parallelToolCalls(safe.parallelToolCalls())
                .executionConfig(ExecutionConfig.builder()
                        .timeout(requirePositive(timeout, "timeout"))
                        .maxAttempts(safe.maximumAttempts())
                        .initialBackoff(safeInitialBackoff)
                        .maxBackoff(safeMaximumBackoff)
                        .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                        .build());
        safe.temperature().ifPresent(value -> target.temperature(value.doubleValue()));
        safe.topP().ifPresent(value -> target.topP(value.doubleValue()));
        safe.maximumOutputTokens().ifPresent(value -> target.maxTokens(Math.toIntExact(value)));
        safe.seed().ifPresent(target::seed);
        if (safe.reasoningMode() == AgentReasoningMode.ENABLED) {
            target.reasoningEffort("medium");
        } else if (safe.reasoningMode() == AgentReasoningMode.DISABLED) {
            target.reasoningEffort("low");
        }
        return target.build();
    }

    private static Duration requirePositive(Duration value, String field) {
        Duration required = Objects.requireNonNull(value, field);
        if (required.isZero() || required.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return required;
    }
}
