package io.crewscope.application.retrieval;

import io.crewscope.domain.knowledge.KnowledgeEntryId;
import java.util.Objects;
import java.util.Optional;

/**
 * Optional listing constraints for Team knowledge-index jobs (M10-I01c). An empty
 * filter lists both sources in every state; an {@code entryId} narrows the listing to
 * that entry's jobs (M13b-A04/R17 — the only anchor a reader can correlate with their
 * own publication), which necessarily excludes repository builds.
 */
public record KnowledgeIndexJobFilter(
        Optional<KnowledgeIndexJobSource> source,
        Optional<KnowledgeIndexJobStatus> status,
        Optional<KnowledgeEntryId> entryId) {

    public KnowledgeIndexJobFilter {
        source = Objects.requireNonNull(source, "source");
        status = Objects.requireNonNull(status, "status");
        entryId = Objects.requireNonNull(entryId, "entryId");
    }

    /** No constraint: every job of the Team, both sources, every state. */
    public static KnowledgeIndexJobFilter all() {
        return new KnowledgeIndexJobFilter(Optional.empty(), Optional.empty(), Optional.empty());
    }
}
