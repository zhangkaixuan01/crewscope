package io.crewscope.domain.shared.id;

import java.util.UUID;

/** Strongly typed identifier for one immutable stored artifact. */
public record ArtifactId(UUID value) implements AggregateId {

    public ArtifactId {
        value = AggregateId.requireValue(value, "ArtifactId");
    }

    public static ArtifactId generate() {
        return new ArtifactId(AggregateId.generateValue());
    }

    public static ArtifactId from(String value) {
        return new ArtifactId(AggregateId.parseCanonical(value, "ArtifactId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
