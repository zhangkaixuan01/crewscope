package io.crewscope.domain.workgraph;

/**
 * Relation kinds accepted on the API surface (ADR-033).
 *
 * <p>Only {@code BLOCKS} and {@code SPLIT_FROM} are stored. {@code DEPENDS_ON(X, Y)} is the
 * submission view of {@code BLOCKS(Y, X)} and is normalized at construction, so the store never
 * holds two blocking directions of the same pair.
 */
public enum WorkItemRelationType {
    BLOCKS,
    DEPENDS_ON,
    SPLIT_FROM;

    /** Returns the kind persisted for a submission of this type. */
    public WorkItemRelationType storageKind() {
        return this == DEPENDS_ON ? BLOCKS : this;
    }

    /** Returns whether submitting this type swaps the endpoints to reach the canonical edge. */
    public boolean swapsEndpointsOnSubmit() {
        return this == DEPENDS_ON;
    }

    /**
     * Returns whether this kind participates in scheduling cycle detection. Only blocking edges
     * do; {@code SPLIT_FROM} is provenance, not ordering — {@code A SPLIT_FROM B} together with
     * {@code B BLOCKS A} is a legal graph.
     */
    public boolean participatesInScheduling() {
        return storageKind() == BLOCKS;
    }
}
