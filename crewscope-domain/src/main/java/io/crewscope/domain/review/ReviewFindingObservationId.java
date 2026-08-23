package io.crewscope.domain.review;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one duplicate Finding observation retained for audit. */
public record ReviewFindingObservationId(UUID value) implements AggregateId {

    public ReviewFindingObservationId {
        value = AggregateId.requireValue(value, "ReviewFindingObservationId");
    }

    public static ReviewFindingObservationId generate() {
        return new ReviewFindingObservationId(AggregateId.generateValue());
    }

    public static ReviewFindingObservationId from(String value) {
        return new ReviewFindingObservationId(
                AggregateId.parseCanonical(value, "ReviewFindingObservationId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
