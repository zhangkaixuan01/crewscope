package io.crewscope.application.artifact;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Bounded cleanup request for content whose Tombstone and retention period are complete. */
public record ArtifactPurgeRequest(UtcTimestamp eligibleBefore, int batchSize) {

    public static final int MAX_BATCH_SIZE = 1_000;

    public ArtifactPurgeRequest {
        Objects.requireNonNull(eligibleBefore, "eligibleBefore");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "batchSize must be between 1 and " + MAX_BATCH_SIZE);
        }
    }
}
