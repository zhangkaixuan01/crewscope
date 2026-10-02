package io.crewscope.domain.knowledge;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Monotonic published revision of one stable Knowledge entry. */
public record KnowledgeEntryRevision(long value)
        implements Comparable<KnowledgeEntryRevision> {

    public KnowledgeEntryRevision {
        if (value < 1) {
            throw new DomainValidationException(
                    "knowledgeEntry.revision", "must be positive");
        }
    }

    public KnowledgeEntryRevision next() {
        if (value == Long.MAX_VALUE) {
            throw new DomainValidationException(
                    "knowledgeEntry.revision", "must not overflow");
        }
        return new KnowledgeEntryRevision(value + 1);
    }

    @Override
    public int compareTo(KnowledgeEntryRevision other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
