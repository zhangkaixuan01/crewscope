package io.crewscope.domain.review;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one versioned Review request. */
public record ReviewRequestId(UUID value) implements AggregateId {

    public ReviewRequestId {
        value = AggregateId.requireValue(value, "ReviewRequestId");
    }

    public static ReviewRequestId generate() {
        return new ReviewRequestId(AggregateId.generateValue());
    }

    public static ReviewRequestId from(String value) {
        return new ReviewRequestId(AggregateId.parseCanonical(value, "ReviewRequestId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
