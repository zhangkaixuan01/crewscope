package io.crewscope.agentscope.coding;

import io.agentscope.core.model.ChatUsage;
import io.crewscope.domain.model.ModelCallAttribution;
import java.util.Objects;
import java.util.Optional;

/**
 * Provider-reported token usage for one logical Coding Specialist model call. Attribution is
 * present exactly when the call served a resolved (pinned) model pair; env-slot calls stay
 * unattributed and their usage is later reported without a usage fact (M10-F03 contract).
 */
public record CodingSpecialistModelUsage(
        long inputTokens,
        long outputTokens,
        long cachedTokens,
        long totalTokens,
        Optional<ModelCallAttribution> attribution) {

    public CodingSpecialistModelUsage {
        if (inputTokens < 0
                || outputTokens < 0
                || cachedTokens < 0
                || totalTokens < 0
                || cachedTokens > inputTokens
                || totalTokens != inputTokens + outputTokens) {
            throw new IllegalArgumentException("model usage must be non-negative and consistent");
        }
        attribution = Objects.requireNonNull(attribution, "attribution");
    }

    public CodingSpecialistModelUsage(
            long inputTokens, long outputTokens, long cachedTokens, long totalTokens) {
        this(inputTokens, outputTokens, cachedTokens, totalTokens, Optional.empty());
    }

    static CodingSpecialistModelUsage from(ChatUsage usage, ModelCallAttribution attribution) {
        return new CodingSpecialistModelUsage(
                usage == null ? 0 : usage.getInputTokens(),
                usage == null ? 0 : usage.getOutputTokens(),
                usage == null ? 0 : usage.getCachedTokens(),
                usage == null ? 0 : usage.getTotalTokens(),
                Optional.ofNullable(attribution));
    }
}
