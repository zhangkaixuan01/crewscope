package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** Explicit provider-neutral GenerateOptions whitelist with no connection override surface. */
public record SafeModelGenerateOptions(
        Optional<BigDecimal> temperature,
        Optional<BigDecimal> topP,
        Optional<Long> maximumOutputTokens,
        AgentReasoningMode reasoningMode,
        boolean cacheEnabled,
        boolean parallelToolCalls,
        Optional<Long> seed,
        int maximumAttempts) {

    public static final long MAXIMUM_OUTPUT_TOKEN_CEILING = 10_000_000;
    public static final int MAXIMUM_ATTEMPTS_CEILING = 10;

    public SafeModelGenerateOptions {
        temperature = normalizeDecimal(temperature, "temperature", BigDecimal.ZERO, new BigDecimal("2"), true);
        topP = normalizeDecimal(topP, "topP", BigDecimal.ZERO, BigDecimal.ONE, false);
        maximumOutputTokens = Objects.requireNonNull(maximumOutputTokens, "maximumOutputTokens");
        maximumOutputTokens.ifPresent(value -> {
            if (value < 1 || value > MAXIMUM_OUTPUT_TOKEN_CEILING) {
                throw new DomainValidationException(
                        "agentConfiguration.generateOptions.maximumOutputTokens",
                        "must be between 1 and " + MAXIMUM_OUTPUT_TOKEN_CEILING);
            }
        });
        reasoningMode = Objects.requireNonNull(reasoningMode, "reasoningMode");
        seed = Objects.requireNonNull(seed, "seed");
        if (maximumAttempts < 1 || maximumAttempts > MAXIMUM_ATTEMPTS_CEILING) {
            throw new DomainValidationException(
                    "agentConfiguration.generateOptions.maximumAttempts",
                    "must be between 1 and " + MAXIMUM_ATTEMPTS_CEILING);
        }
    }

    public static SafeModelGenerateOptions defaults() {
        return new SafeModelGenerateOptions(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                AgentReasoningMode.DEFAULT,
                true,
                false,
                Optional.empty(),
                1);
    }

    void appendCanonical(StringBuilder target) {
        AgentConfigurationHash.append(
                target, temperature.map(BigDecimal::toPlainString).orElse("temperature:default"));
        AgentConfigurationHash.append(
                target, topP.map(BigDecimal::toPlainString).orElse("topP:default"));
        AgentConfigurationHash.append(
                target,
                maximumOutputTokens.map(Object::toString).orElse("maximumOutputTokens:default"));
        AgentConfigurationHash.append(target, reasoningMode.name());
        AgentConfigurationHash.append(target, Boolean.toString(cacheEnabled));
        AgentConfigurationHash.append(target, Boolean.toString(parallelToolCalls));
        AgentConfigurationHash.append(target, seed.map(Object::toString).orElse("seed:default"));
        AgentConfigurationHash.append(target, Integer.toString(maximumAttempts));
    }

    private static Optional<BigDecimal> normalizeDecimal(
            Optional<BigDecimal> value,
            String field,
            BigDecimal minimum,
            BigDecimal maximum,
            boolean includeMinimum) {
        Optional<BigDecimal> required = Objects.requireNonNull(value, field);
        if (required.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal normalized = Objects.requireNonNull(required.orElseThrow(), field)
                .stripTrailingZeros();
        boolean belowMinimum = includeMinimum
                ? normalized.compareTo(minimum) < 0
                : normalized.compareTo(minimum) <= 0;
        if (belowMinimum || normalized.compareTo(maximum) > 0) {
            throw new DomainValidationException(
                    "agentConfiguration.generateOptions." + field,
                    "is outside the allowed provider-neutral range");
        }
        return Optional.of(normalized);
    }
}
