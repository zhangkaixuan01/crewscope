package io.crewscope.domain.retrieval;

/**
 * The three explicit degradation reasons replacing silent fallbacks (M10-S01 §3.4).
 * Retrieval is fail-closed: an empty answer must carry one of these codes.
 */
public enum DegradationReasonCode {
    RETRIEVAL_DISABLED,
    NO_MATCHING_GENERATION,
    EMBEDDING_PROVIDER_UNAVAILABLE
}
