package io.crewscope.domain.knowledge;

/**
 * Head-level classification of one Team Knowledge entry. Organisational metadata
 * only: never part of the version content hash, so reclassifying an entry never
 * mints a new revision and never collides with the unchanged-content rule.
 */
public enum KnowledgeCategory {
    CONVENTION,
    RUNBOOK,
    DECISION,
    GUIDE,
    OTHER
}
