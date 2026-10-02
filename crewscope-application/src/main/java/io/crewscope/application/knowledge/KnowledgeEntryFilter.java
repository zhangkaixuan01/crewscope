package io.crewscope.application.knowledge;

import io.crewscope.domain.knowledge.KnowledgeEntryStatus;
import java.util.Set;

/**
 * Read-side status filter for Team knowledge listings. Pagination and ordering are
 * A02's delivery and deliberately absent from the frozen contract.
 */
public record KnowledgeEntryFilter(Set<KnowledgeEntryStatus> statuses) {

    public KnowledgeEntryFilter {
        if (statuses == null || statuses.isEmpty()) {
            throw new IllegalArgumentException("statuses must not be empty");
        }
        statuses = Set.copyOf(statuses);
    }

    public static KnowledgeEntryFilter all() {
        return new KnowledgeEntryFilter(
                java.util.EnumSet.allOf(KnowledgeEntryStatus.class));
    }

    public static KnowledgeEntryFilter effectivelyPublished() {
        return new KnowledgeEntryFilter(Set.of(KnowledgeEntryStatus.PUBLISHED));
    }
}
