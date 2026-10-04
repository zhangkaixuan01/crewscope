package io.crewscope.server.config.application;

import io.crewscope.domain.retrieval.InjectionBudgetPlanner;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Prompt injection deployment switches and the frozen budget defaults (M10-I02b,
 * S01 §3.3). {@code enabled} defaults off: a false switch assembles nothing, seals
 * nothing and injects nothing. The token budget carries the frozen defaults —
 * 8192 total split 3072/4096/1024 across knowledge, repository chunks and memory —
 * with a hard 32768 ceiling on the total: a wider value is an operator error and
 * refuses startup rather than silently overflowing the instruction bound. Layer
 * budgets may be zero but may never sum past the total.
 */
@ConfigurationProperties(prefix = "crewscope.knowledge.injection")
public class InjectionBudgetProperties {

    /** Hard ceiling on the configured total; beyond this the context cannot stay bounded. */
    public static final long MAX_TOTAL_TOKENS = 32768;

    private boolean enabled = false;
    private long totalTokens = 8192;
    private long knowledgeTokens = 3072;
    private long chunkTokens = 4096;
    private long memoryTokens = 1024;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public long getKnowledgeTokens() {
        return knowledgeTokens;
    }

    public void setKnowledgeTokens(long knowledgeTokens) {
        this.knowledgeTokens = knowledgeTokens;
    }

    public long getChunkTokens() {
        return chunkTokens;
    }

    public void setChunkTokens(long chunkTokens) {
        this.chunkTokens = chunkTokens;
    }

    public long getMemoryTokens() {
        return memoryTokens;
    }

    public void setMemoryTokens(long memoryTokens) {
        this.memoryTokens = memoryTokens;
    }

    /**
     * Fails fast at startup on an incoherent budget: the total inside its supported
     * range, layer budgets non-negative and their sum never past the total. The
     * domain limits record itself only checks non-negativity — this is where the
     * deployment-level shape contract closes.
     */
    public InjectionBudgetPlanner.InjectionBudgetLimits validatedLimits() {
        if (totalTokens < 1 || totalTokens > MAX_TOTAL_TOKENS) {
            throw new IllegalStateException(
                    "crewscope.knowledge.injection.total-tokens must be between 1 and "
                            + MAX_TOTAL_TOKENS);
        }
        if (knowledgeTokens < 0 || chunkTokens < 0 || memoryTokens < 0) {
            throw new IllegalStateException(
                    "crewscope.knowledge.injection layer budgets must not be negative");
        }
        long layered = knowledgeTokens + chunkTokens + memoryTokens;
        if (layered > totalTokens) {
            throw new IllegalStateException(
                    "crewscope.knowledge.injection layer budgets ("
                            + layered + ") must not exceed total-tokens (" + totalTokens + ")");
        }
        return new InjectionBudgetPlanner.InjectionBudgetLimits(
                totalTokens, knowledgeTokens, chunkTokens, memoryTokens);
    }
}
