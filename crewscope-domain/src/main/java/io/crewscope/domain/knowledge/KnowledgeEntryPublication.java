package io.crewscope.domain.knowledge;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/**
 * Result of {@link KnowledgeEntry#publish}: the advanced head and the appended immutable
 * version. Persistence must commit both rows in one transaction (M10-D01 port contract).
 */
public record KnowledgeEntryPublication(
        KnowledgeEntry entry, KnowledgeEntryVersion version) {

    public KnowledgeEntryPublication {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(version, "version");
        if (!entry.id().equals(version.entryId())
                || !entry.scope().equals(version.scope())) {
            throw new DomainValidationException(
                    "knowledgeEntryPublication",
                    "entry and version must belong to the same aggregate");
        }
    }
}
