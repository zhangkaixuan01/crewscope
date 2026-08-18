package io.crewscope.domain.coding;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one immutable final DiffArtifact. */
public record DiffArtifactId(UUID value) implements AggregateId {

    public DiffArtifactId {
        value = AggregateId.requireValue(value, "DiffArtifactId");
    }

    public static DiffArtifactId generate() {
        return new DiffArtifactId(AggregateId.generateValue());
    }

    public static DiffArtifactId from(String value) {
        return new DiffArtifactId(AggregateId.parseCanonical(value, "DiffArtifactId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
