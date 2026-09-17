package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewLineComment;
import io.crewscope.domain.review.ReviewLineCommentId;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/** Tenant-scoped persistence port for Review line comments. */
public interface ReviewLineCommentRepository {
    ReviewLineComment create(ReviewLineComment comment, String idempotencyKey);
    ReviewLineComment update(ReviewLineComment comment, long expectedVersion, String idempotencyKey);
    Optional<ReviewLineComment> findById(OrganizationId organizationId, ReviewLineCommentId id);

    /**
     * Resolves a command key to its comment. A create key stays resolvable for the lifetime of the
     * comment; an update key resolves until a later update replaces it, which is why only the newest
     * update can be replayed.
     */
    Optional<ReviewLineComment> findByIdempotencyKey(OrganizationId organizationId, String idempotencyKey);
    List<ReviewLineComment> findByRequest(
            OrganizationId organizationId, ReviewRequestId reviewRequestId, String afterId, int limit);
}
