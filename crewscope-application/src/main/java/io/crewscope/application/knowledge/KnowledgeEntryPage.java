package io.crewscope.application.knowledge;

import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One bounded knowledge listing page with the next keyset cursor, if more pages remain. */
public record KnowledgeEntryPage(
        List<KnowledgeEntry> items, Optional<KnowledgeEntryKey> nextEntryKey) {

    public KnowledgeEntryPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextEntryKey = Objects.requireNonNull(nextEntryKey, "nextEntryKey");
        if (items.isEmpty() && nextEntryKey.isPresent()) {
            throw new IllegalArgumentException(
                    "Empty knowledge entry page cannot have a next cursor");
        }
    }
}
