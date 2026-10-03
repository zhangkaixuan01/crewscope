package io.crewscope.application.retrieval;

import java.util.Objects;
import java.util.Optional;

/**
 * Optional listing constraints for Team knowledge-index jobs (M10-I01c). An empty
 * filter lists both sources in every state.
 */
public record KnowledgeIndexJobFilter(
        Optional<KnowledgeIndexJobSource> source, Optional<KnowledgeIndexJobStatus> status) {

    public KnowledgeIndexJobFilter {
        source = Objects.requireNonNull(source, "source");
        status = Objects.requireNonNull(status, "status");
    }

    /** No constraint: every job of the Team, both sources, every state. */
    public static KnowledgeIndexJobFilter all() {
        return new KnowledgeIndexJobFilter(Optional.empty(), Optional.empty());
    }
}
