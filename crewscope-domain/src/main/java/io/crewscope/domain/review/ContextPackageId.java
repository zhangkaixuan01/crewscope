package io.crewscope.domain.review;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one immutable bounded Reviewer context package. */
public record ContextPackageId(UUID value) implements AggregateId {

    public ContextPackageId {
        value = AggregateId.requireValue(value, "ContextPackageId");
    }

    public static ContextPackageId generate() {
        return new ContextPackageId(AggregateId.generateValue());
    }

    public static ContextPackageId from(String value) {
        return new ContextPackageId(AggregateId.parseCanonical(value, "ContextPackageId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
