package io.crewscope.domain.review;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one append-only member Gate decision. */
public record ReviewDecisionId(UUID value) implements AggregateId {

    public ReviewDecisionId {
        value = AggregateId.requireValue(value, "ReviewDecisionId");
    }

    public static ReviewDecisionId generate() {
        return new ReviewDecisionId(AggregateId.generateValue());
    }

    public static ReviewDecisionId from(String value) {
        return new ReviewDecisionId(AggregateId.parseCanonical(value, "ReviewDecisionId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
