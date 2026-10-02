package io.crewscope.domain.knowledge;

/**
 * Lifecycle of one Knowledge entry head. Only {@code PUBLISHED} entries are retrievable;
 * {@code DELETED} is a tombstone: the entry and its versions stay persisted for source
 * attribution but can never be published again.
 */
public enum KnowledgeEntryStatus {
    DRAFT,
    PUBLISHED,
    RETIRED,
    DELETED
}
