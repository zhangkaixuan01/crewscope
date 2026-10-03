package io.crewscope.application.knowledge;

import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One bounded version-history page with the next keyset cursor, if more pages remain. */
public record KnowledgeEntryVersionPage(
        List<KnowledgeEntryVersion> items, Optional<KnowledgeEntryRevision> nextRevision) {

    public KnowledgeEntryVersionPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextRevision = Objects.requireNonNull(nextRevision, "nextRevision");
        if (items.isEmpty() && nextRevision.isPresent()) {
            throw new IllegalArgumentException(
                    "Empty knowledge version page cannot have a next cursor");
        }
    }
}
