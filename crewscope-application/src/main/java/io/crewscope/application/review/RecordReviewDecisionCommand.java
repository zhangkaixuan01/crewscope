package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewDecisionType;
import java.util.Objects;

/** Human Gate input; reviewer identity and eligibility are always resolved by the server. */
public record RecordReviewDecisionCommand(ReviewDecisionType type, String rationale) {

    public RecordReviewDecisionCommand {
        type = Objects.requireNonNull(type, "type");
        rationale = Objects.requireNonNull(rationale, "rationale");
    }
}
