package io.crewscope.agentscope.coding;

import io.agentscope.core.model.ChatUsage;

/** Provider-reported token usage for one logical Coding Specialist model call. */
public record CodingSpecialistModelUsage(
        long inputTokens, long outputTokens, long cachedTokens, long totalTokens) {

    public CodingSpecialistModelUsage {
        if (inputTokens < 0
                || outputTokens < 0
                || cachedTokens < 0
                || totalTokens < 0
                || cachedTokens > inputTokens
                || totalTokens != inputTokens + outputTokens) {
            throw new IllegalArgumentException("model usage must be non-negative and consistent");
        }
    }

    static CodingSpecialistModelUsage from(ChatUsage usage) {
        if (usage == null) {
            return new CodingSpecialistModelUsage(0, 0, 0, 0);
        }
        return new CodingSpecialistModelUsage(
                usage.getInputTokens(),
                usage.getOutputTokens(),
                usage.getCachedTokens(),
                usage.getTotalTokens());
    }
}
