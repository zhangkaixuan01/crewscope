package io.crewscope.domain.retrieval;

/**
 * Frozen vocabulary of one index generation lifecycle (mirrors the ADR-020 projection
 * generation terms and the V34 import-job states). M10-D01 freezes only the vocabulary;
 * the state machine itself is I01's delivery.
 */
public enum GenerationStatus {
    BUILDING,
    VALIDATING,
    ACTIVE,
    RETIRED,
    FAILED,
    CANCELLED
}
