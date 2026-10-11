package io.crewscope.server.api;

/** Synchronous monthly usage-rollup rebuild outcome (M10-F03). */
public record RollupRebuildResponse(String status, long projectedFacts) {

    static RollupRebuildResponse completed(long projectedFacts) {
        return new RollupRebuildResponse("COMPLETED", projectedFacts);
    }
}
