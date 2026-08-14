package io.crewscope.domain.task;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identifier for persisted runtime Artifact metadata. */
public record RuntimeArtifactId(UUID value) implements AggregateId {
    public RuntimeArtifactId {
        value = AggregateId.requireValue(value, "RuntimeArtifactId");
    }

    public static RuntimeArtifactId generate() {
        return new RuntimeArtifactId(AggregateId.generateValue());
    }

    public static RuntimeArtifactId from(String value) {
        return new RuntimeArtifactId(AggregateId.parseCanonical(value, "RuntimeArtifactId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
