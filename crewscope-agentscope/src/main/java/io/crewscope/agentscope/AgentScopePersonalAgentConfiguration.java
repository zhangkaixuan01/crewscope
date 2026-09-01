package io.crewscope.agentscope;

import io.agentscope.core.model.Model;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Objects;
import java.util.Optional;

/** Version-pinned AgentScope model and prompt configuration for one Personal Agent profile. */
public record AgentScopePersonalAgentConfiguration(
        AgentProfileId agentProfileId,
        long agentProfileVersion,
        String modelId,
        Optional<String> fallbackModelId,
        String systemPrompt,
        int maxIterations,
        int maxRetries,
        Optional<Model> primaryModel,
        Optional<Model> fallbackModel) {

    /** Backward-compatible constructor for legacy environment-backed tests/configuration. */
    public AgentScopePersonalAgentConfiguration(
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            String modelId,
            Optional<String> fallbackModelId,
            String systemPrompt,
            int maxIterations,
            int maxRetries) {
        this(
                agentProfileId,
                agentProfileVersion,
                modelId,
                fallbackModelId,
                systemPrompt,
                maxIterations,
                maxRetries,
                Optional.empty(),
                Optional.empty());
    }

    public AgentScopePersonalAgentConfiguration {
        agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        if (agentProfileVersion < 0) {
            throw new IllegalArgumentException("agentProfileVersion must not be negative");
        }
        modelId = requireText(modelId, "modelId", 200, false);
        fallbackModelId = Objects.requireNonNull(fallbackModelId, "fallbackModelId")
                .map(value -> requireText(value, "fallbackModelId", 200, false));
        primaryModel = Objects.requireNonNull(primaryModel, "primaryModel");
        fallbackModel = Objects.requireNonNull(fallbackModel, "fallbackModel");
        if (fallbackModel.isPresent() && primaryModel.isEmpty()) {
            throw new IllegalArgumentException("fallbackModel requires primaryModel");
        }
        if (fallbackModelId.filter(modelId::equals).isPresent()) {
            throw new IllegalArgumentException("fallbackModelId must differ from modelId");
        }
        systemPrompt = requireText(systemPrompt, "systemPrompt", 20_000, true);
        if (maxIterations < 1 || maxIterations > 100) {
            throw new IllegalArgumentException("maxIterations must be between 1 and 100");
        }
        if (maxRetries < 1 || maxRetries > 10) {
            throw new IllegalArgumentException("maxRetries must be between 1 and 10");
        }
    }

    private static String requireText(
            String value, String field, int maxLength, boolean allowLayoutCharacters) {
        String required = Objects.requireNonNull(value, field).strip();
        boolean invalidControl = required.chars().anyMatch(character ->
                Character.isISOControl(character)
                        && (!allowLayoutCharacters || (character != '\n' && character != '\t')));
        if (required.isEmpty() || required.length() > maxLength || invalidControl) {
            throw new IllegalArgumentException(
                    field + " must contain 1 to " + maxLength + " safe characters");
        }
        return required;
    }
}
