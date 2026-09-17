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

    public SafeModelGenerateOptions {
        temperature = normalizeDecimal(temperature, AgentGenerateOptionsLimits.TEMPERATURE);
        topP = normalizeDecimal(topP, AgentGenerateOptionsLimits.TOP_P);
        maximumOutputTokens = normalizeInteger(maximumOutputTokens, AgentGenerateOptionsLimits.MAXIMUM_OUTPUT_TOKENS);
        reasoningMode = Objects.requireNonNull(reasoningMode, "reasoningMode");
        seed = Objects.requireNonNull(seed, "seed");
        if (!AgentGenerateOptionsLimits.MAXIMUM_ATTEMPTS.accepts(maximumAttempts)) {
            throw outsideRange(AgentGenerateOptionsLimits.MAXIMUM_ATTEMPTS);
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
            AgentGenerateOptionsLimits.Limit limit) {
        Optional<BigDecimal> required = Objects.requireNonNull(value, limit.field());
        if (required.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal normalized = Objects.requireNonNull(required.orElseThrow(), limit.field())
                .stripTrailingZeros();
        if (!limit.accepts(normalized)) {
            throw outsideRange(limit);
        }
        return Optional.of(normalized);
    }

    private static Optional<Long> normalizeInteger(
            Optional<Long> value,
            AgentGenerateOptionsLimits.Limit limit) {
        Optional<Long> required = Objects.requireNonNull(value, limit.field());
        required.ifPresent(present -> {
            if (!limit.accepts(present)) {
                throw outsideRange(limit);
            }
        });
        return required;
    }

    /**
     * One rejection message for every bound, so the sentence a member reads in the form and the
     * sentence the server answers a rejected command with come from the same numbers.
     */
    private static DomainValidationException outsideRange(AgentGenerateOptionsLimits.Limit limit) {
        return new DomainValidationException(
                "agentConfiguration.generateOptions." + limit.field(),
                "must be between " + limit.minimum() + " and " + limit.maximum());
    }
}
