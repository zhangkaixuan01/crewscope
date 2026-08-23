package io.crewscope.domain.review;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one changes-requested modification round. */
public record ReviewModificationRoundId(UUID value) implements AggregateId {

    public ReviewModificationRoundId {
        value = AggregateId.requireValue(value, "ReviewModificationRoundId");
    }

    public static ReviewModificationRoundId generate() {
        return new ReviewModificationRoundId(AggregateId.generateValue());
    }

    public static ReviewModificationRoundId from(String value) {
        return new ReviewModificationRoundId(
                AggregateId.parseCanonical(value, "ReviewModificationRoundId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
