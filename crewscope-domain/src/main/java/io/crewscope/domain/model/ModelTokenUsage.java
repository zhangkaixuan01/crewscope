package io.crewscope.domain.model;

/**
 * Token counters of one model call attempt, mirroring the sanitized runtime invariant of
 * {@code AgentRunEventRecorded.TokenUsage}: non-negative, cached tokens are a subset of
 * input, and total is exactly input plus output. Zero values mean the Provider did not
 * report that counter — not that no tokens were consumed; price aggregation (F03) owns
 * mapping "missing" onto UNKNOWN.
 */
public record ModelTokenUsage(
        long inputTokens, long outputTokens, long cachedTokens, long totalTokens) {

    public ModelTokenUsage {
        if (inputTokens < 0
                || outputTokens < 0
                || cachedTokens < 0
                || totalTokens < 0
                || cachedTokens > inputTokens
                || totalTokens != inputTokens + outputTokens) {
            throw new IllegalArgumentException("token usage must be non-negative and consistent");
        }
    }

    /** Usage shape for a Provider that reports no counters at all. */
    public static ModelTokenUsage unreported() {
        return new ModelTokenUsage(0, 0, 0, 0);
    }
}
