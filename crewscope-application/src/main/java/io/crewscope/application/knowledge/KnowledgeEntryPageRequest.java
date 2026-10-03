package io.crewscope.application.knowledge;

import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import java.util.Objects;
import java.util.Optional;

/**
 * Keyset pagination for Team knowledge listings, ordered by entry key ascending. The
 * cursor is the plain last-seen entry key; a missing key yields the first page.
 */
public record KnowledgeEntryPageRequest(
        Optional<KnowledgeEntryKey> afterEntryKey, int limit) {

    public KnowledgeEntryPageRequest {
        afterEntryKey = Objects.requireNonNull(afterEntryKey, "afterEntryKey");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Knowledge entry page limit must be within [1, 100]");
        }
    }
}
