package io.crewscope.domain.review;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identifier for one Review line comment. */
public record ReviewLineCommentId(UUID value) implements AggregateId {
    public ReviewLineCommentId {
        value = AggregateId.requireValue(value, "ReviewLineCommentId");
    }

    public static ReviewLineCommentId generate() {
        return new ReviewLineCommentId(AggregateId.generateValue());
    }

    public static ReviewLineCommentId from(String value) {
        return new ReviewLineCommentId(AggregateId.parseCanonical(value, "ReviewLineCommentId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
