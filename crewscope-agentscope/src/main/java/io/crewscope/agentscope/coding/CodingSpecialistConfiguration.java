package io.crewscope.agentscope.coding;

import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Objects;
import java.util.Optional;

/** Version-pinned AgentScope configuration for one CrewScope Coding Specialist profile. */
public record CodingSpecialistConfiguration(
        AgentProfileId agentProfileId,
        long agentProfileVersion,
        String modelId,
        Optional<String> fallbackModelId,
        String compactionModelId,
        String systemPrompt,
        int maxIterations,
        int maxRetries,
        double temperature,
        double topP,
        int maxOutputTokens,
        int compactionTriggerMessages,
        int compactionKeepMessages,
        int toolResultEvictionChars,
        int toolResultPreviewChars) {

    public CodingSpecialistConfiguration {
        agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        if (agentProfileVersion < 0) {
            throw new IllegalArgumentException("agentProfileVersion must not be negative");
        }
        modelId = requireText(modelId, "modelId", 200, false);
        fallbackModelId = Objects.requireNonNull(fallbackModelId, "fallbackModelId")
                .map(value -> requireText(value, "fallbackModelId", 200, false));
        if (fallbackModelId.filter(modelId::equals).isPresent()) {
            throw new IllegalArgumentException("fallbackModelId must differ from modelId");
        }
        compactionModelId = requireText(
                compactionModelId, "compactionModelId", 200, false);
        systemPrompt = requireText(systemPrompt, "systemPrompt", 30_000, true);
        requireRange(maxIterations, 1, 200, "maxIterations");
        requireRange(maxRetries, 1, 10, "maxRetries");
        requireDoubleRange(temperature, 0.0, 2.0, "temperature");
        requireDoubleRange(topP, 0.0, 1.0, "topP");
        requireRange(maxOutputTokens, 1, 131_072, "maxOutputTokens");
        requireRange(compactionTriggerMessages, 6, 500, "compactionTriggerMessages");
        requireRange(compactionKeepMessages, 2, 100, "compactionKeepMessages");
        if (compactionKeepMessages >= compactionTriggerMessages) {
            throw new IllegalArgumentException(
                    "compactionKeepMessages must be below compactionTriggerMessages");
        }
        requireRange(toolResultEvictionChars, 128, 1_000_000, "toolResultEvictionChars");
        requireRange(toolResultPreviewChars, 16, 20_000, "toolResultPreviewChars");
        if (Math.multiplyExact(toolResultPreviewChars, 2) >= toolResultEvictionChars) {
            throw new IllegalArgumentException(
                    "toolResultPreviewChars must leave room for an evicted result body");
        }
    }

    private static String requireText(
            String value, String field, int maximumLength, boolean layoutAllowed) {
        String required = Objects.requireNonNull(value, field).strip();
        boolean invalidControl = required.chars().anyMatch(character ->
                Character.isISOControl(character)
                        && (!layoutAllowed || (character != '\n' && character != '\t')));
        if (required.isEmpty() || required.length() > maximumLength || invalidControl) {
            throw new IllegalArgumentException(field + " contains invalid text");
        }
        return required;
    }

    private static void requireRange(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    field + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void requireDoubleRange(
            double value, double minimum, double maximum, String field) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    field + " must be between " + minimum + " and " + maximum);
        }
    }
}
