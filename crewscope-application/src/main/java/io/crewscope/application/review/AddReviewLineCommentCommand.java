package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewCommentAnchor;
import java.util.Objects;

/** Command for adding one Markdown comment anchored to a Review Diff line. */
public record AddReviewLineCommentCommand(ReviewCommentAnchor anchor, String content) {
    public AddReviewLineCommentCommand {
        anchor = Objects.requireNonNull(anchor, "anchor");
        content = Objects.requireNonNull(content, "content");
    }
}
