package io.crewscope.application.memory;

/**
 * Receipt of one memory clearance (M10-I02a): how many current-generation entries across
 * every policy space were deleted, and the new monotonic clearance generation. Repeating a
 * clear is harmless (it advances the generation again and reports zero entries).
 */
public record AgentMemoryClearance(long clearedCount, long clearanceGeneration) {

    public AgentMemoryClearance {
        if (clearedCount < 0) {
            throw new IllegalArgumentException("clearedCount must not be negative");
        }
        if (clearanceGeneration < 0) {
            throw new IllegalArgumentException("clearanceGeneration must not be negative");
        }
    }
}
