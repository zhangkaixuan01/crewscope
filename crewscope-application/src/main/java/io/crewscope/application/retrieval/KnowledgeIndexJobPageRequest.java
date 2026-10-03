package io.crewscope.application.retrieval;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Keyset pagination for Team knowledge-index job listings (M10-I01c), ordered by
 * {@code (createdAt, id)} ascending. The cursor is the plain last-seen job id of the
 * previous page; a missing id yields the first page.
 */
public record KnowledgeIndexJobPageRequest(Optional<UUID> afterJobId, int limit) {

    public KnowledgeIndexJobPageRequest {
        afterJobId = Objects.requireNonNull(afterJobId, "afterJobId");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Knowledge index job page limit must be within [1, 100]");
        }
    }
}
