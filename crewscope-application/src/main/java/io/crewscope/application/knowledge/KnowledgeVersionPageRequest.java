package io.crewscope.application.knowledge;

import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import java.util.Objects;
import java.util.Optional;

/**
 * Keyset pagination for one entry's version history, ordered by revision ascending.
 * The cursor is the plain last-seen revision; a missing revision yields the first page.
 */
public record KnowledgeVersionPageRequest(
        Optional<KnowledgeEntryRevision> afterRevision, int limit) {

    public KnowledgeVersionPageRequest {
        afterRevision = Objects.requireNonNull(afterRevision, "afterRevision");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Knowledge version page limit must be within [1, 100]");
        }
    }
}
