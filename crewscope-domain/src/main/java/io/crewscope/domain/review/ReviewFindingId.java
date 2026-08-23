package io.crewscope.domain.review;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of the first persisted observation of one Finding fingerprint. */
public record ReviewFindingId(UUID value) implements AggregateId {

    public ReviewFindingId {
        value = AggregateId.requireValue(value, "ReviewFindingId");
    }

    public static ReviewFindingId generate() {
        return new ReviewFindingId(AggregateId.generateValue());
    }

    public static ReviewFindingId from(String value) {
        return new ReviewFindingId(AggregateId.parseCanonical(value, "ReviewFindingId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
