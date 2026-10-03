package io.crewscope.domain.model;

/**
 * Stable classification of why one model call consumed tokens. Defined once with all five
 * S01 §3.9 values even though M10 only emits DISTILLATION yet: the usage-fact table is a
 * cross-cutting ledger, and renumbering the vocabulary later would invalidate stored facts.
 */
public enum ModelUsageRole {
    CHAT_PRIMARY,
    CHAT_FALLBACK,
    COMPACTION,
    EMBEDDING,
    DISTILLATION
}
