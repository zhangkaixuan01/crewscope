package io.crewscope.domain.review;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Exact immutable member Decision used by Action and modification-round authority. */
public record ReviewDecisionReference(
        ReviewDecisionId id,
        long revision,
        ReviewRequestReference reviewRequest,
        ReviewDecisionType type,
        TaskFactHash decisionHash) {

    public ReviewDecisionReference {
        id = Objects.requireNonNull(id, "id");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        reviewRequest = Objects.requireNonNull(reviewRequest, "reviewRequest");
        type = Objects.requireNonNull(type, "type");
        decisionHash = Objects.requireNonNull(decisionHash, "decisionHash");
    }
}
