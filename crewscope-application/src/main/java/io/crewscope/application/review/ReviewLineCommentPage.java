package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewLineComment;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded list result; transport encodes the opaque next cursor. */
public record ReviewLineCommentPage(List<ReviewLineComment> items, Optional<String> next) {
    public ReviewLineCommentPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        next = Objects.requireNonNull(next, "next");
    }
}
