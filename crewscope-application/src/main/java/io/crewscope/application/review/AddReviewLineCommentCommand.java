package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewCommentAnchor;
import java.util.Objects;

/** Command for adding one Markdown comment anchored to a Review Diff line. */
public record AddReviewLineCommentCommand(ReviewCommentAnchor anchor, String content) {
    public AddReviewLineCommentCommand {
        anchor = Objects.requireNonNull(anchor, "anchor");
        /*
         * The domain normalises content before storing it, so the command carries the same form.
         * Otherwise an idempotent replay of the identical request compares the stored (stripped)
         * content against the raw body and reports the key as already used by another request.
         */
        content = Objects.requireNonNull(content, "content").strip();
    }
}
