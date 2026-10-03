package io.crewscope.application.retrieval;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One bounded knowledge-index job page with the next keyset cursor, if more remain. */
public record KnowledgeIndexJobPage(
        List<KnowledgeIndexJob> items, Optional<UUID> nextAfterJobId) {

    public KnowledgeIndexJobPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextAfterJobId = Objects.requireNonNull(nextAfterJobId, "nextAfterJobId");
        if (items.isEmpty() && nextAfterJobId.isPresent()) {
            throw new IllegalArgumentException(
                    "Empty knowledge index job page cannot have a next cursor");
        }
    }
}
