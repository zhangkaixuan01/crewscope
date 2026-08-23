package io.crewscope.domain.review;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of an immutable object submitted for Review. */
public record ReviewSubjectId(UUID value) implements AggregateId {

    public ReviewSubjectId {
        value = AggregateId.requireValue(value, "ReviewSubjectId");
    }

    public static ReviewSubjectId generate() {
        return new ReviewSubjectId(AggregateId.generateValue());
    }

    public static ReviewSubjectId from(String value) {
        return new ReviewSubjectId(AggregateId.parseCanonical(value, "ReviewSubjectId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
