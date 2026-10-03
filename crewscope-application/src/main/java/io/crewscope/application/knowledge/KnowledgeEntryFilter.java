package io.crewscope.application.knowledge;

import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeEntryStatus;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-side filter for Team knowledge listings: status set plus an optional category.
 * Pagination and ordering travel in the page request; other sort keys are deliberately
 * absent from the frozen contract.
 */
public record KnowledgeEntryFilter(
        Set<KnowledgeEntryStatus> statuses, Optional<KnowledgeCategory> category) {

    public KnowledgeEntryFilter(Set<KnowledgeEntryStatus> statuses) {
        this(statuses, Optional.empty());
    }

    public KnowledgeEntryFilter {
        if (statuses == null || statuses.isEmpty()) {
            throw new IllegalArgumentException("statuses must not be empty");
        }
        statuses = Set.copyOf(statuses);
        category = Objects.requireNonNull(category, "category");
    }

    public static KnowledgeEntryFilter all() {
        return new KnowledgeEntryFilter(
                EnumSet.allOf(KnowledgeEntryStatus.class), Optional.empty());
    }

    public static KnowledgeEntryFilter effectivelyPublished() {
        return new KnowledgeEntryFilter(Set.of(KnowledgeEntryStatus.PUBLISHED));
    }

    public static KnowledgeEntryFilter byCategory(
            Set<KnowledgeEntryStatus> statuses, KnowledgeCategory category) {
        return new KnowledgeEntryFilter(statuses, Optional.of(category));
    }
}
