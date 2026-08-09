package io.crewscope.agentscope;

import io.agentscope.core.model.ChatUsage;

/** Safe numeric projection of AgentScope ChatUsage without response content. */
record AgentCallTokenUsage(int inputTokens, int outputTokens, int cachedTokens, int totalTokens) {

    static AgentCallTokenUsage none() {
        return new AgentCallTokenUsage(0, 0, 0, 0);
    }

    static AgentCallTokenUsage from(ChatUsage usage) {
        return usage == null
                ? none()
                : new AgentCallTokenUsage(
                        usage.getInputTokens(),
                        usage.getOutputTokens(),
                        usage.getCachedTokens(),
                        usage.getTotalTokens());
    }
}
