package io.crewscope.application.observability;

/** Persistence Port for the rebuildable monthly usage rollup (M10-F03). */
public interface ModelUsageRollupWriter {

    /**
     * Applies one additive increment, creating the grain row or atomically adding onto
     * the existing one (ON CONFLICT … col = col + EXCLUDED.col semantics).
     */
    void apply(ModelUsageRollupDelta delta);

    /** Deletes every rollup row; the first phase of {@code rebuildAll}. */
    void deleteAll();
}
