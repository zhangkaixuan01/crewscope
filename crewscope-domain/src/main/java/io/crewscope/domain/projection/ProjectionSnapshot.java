package io.crewscope.domain.projection;

import java.util.List;
import java.util.Objects;

/** Canonical count/hash plus ordering health at one exact verification boundary. */
public record ProjectionSnapshot(
        long rowCount,
        ProjectionCanonicalHash canonicalHash,
        long gapCount,
        List<ProjectionFailedPartition> failedPartitions) {

    public ProjectionSnapshot {
        canonicalHash = Objects.requireNonNull(canonicalHash, "canonicalHash");
        failedPartitions = List.copyOf(Objects.requireNonNull(
                failedPartitions, "failedPartitions"));
        if (rowCount < 0 || gapCount < 0) {
            throw new IllegalArgumentException("Projection snapshot counters must not be negative");
        }
        if (failedPartitions.stream().distinct().count() != failedPartitions.size()) {
            throw new IllegalArgumentException("Projection failed partitions must be unique");
        }
    }

    public boolean healthy() {
        return gapCount == 0 && failedPartitions.isEmpty();
    }
}
