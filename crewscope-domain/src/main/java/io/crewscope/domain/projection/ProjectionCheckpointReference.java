package io.crewscope.domain.projection;

import java.util.Objects;

/** Full Generation-scoped reference to a projection partition checkpoint. */
public record ProjectionCheckpointReference(
        ProjectionGenerationKey generationKey, String partitionKey) {

    public static final int MAX_PARTITION_KEY_LENGTH = 240;

    public ProjectionCheckpointReference {
        generationKey = Objects.requireNonNull(generationKey, "generationKey");
        if (partitionKey == null || partitionKey.isBlank()) {
            throw new IllegalArgumentException("partitionKey must not be blank");
        }
        partitionKey = partitionKey.strip();
        if (partitionKey.length() > MAX_PARTITION_KEY_LENGTH) {
            throw new IllegalArgumentException("partitionKey is too long");
        }
    }
}
