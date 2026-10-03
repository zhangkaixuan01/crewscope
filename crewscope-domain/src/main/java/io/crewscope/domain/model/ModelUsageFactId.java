package io.crewscope.domain.model;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identifier of one stable model usage fact (the deduplication key). */
public record ModelUsageFactId(UUID value) implements AggregateId {

    public ModelUsageFactId {
        value = AggregateId.requireValue(value, "ModelUsageFactId");
    }

    public static ModelUsageFactId generate() {
        return new ModelUsageFactId(AggregateId.generateValue());
    }

    public static ModelUsageFactId from(String value) {
        return new ModelUsageFactId(AggregateId.parseCanonical(value, "ModelUsageFactId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
