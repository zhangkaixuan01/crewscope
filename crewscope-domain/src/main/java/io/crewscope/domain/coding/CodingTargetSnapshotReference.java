package io.crewscope.domain.coding;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Closed reference used by retries and future ExecutionWorkspace facts. */
public record CodingTargetSnapshotReference(
        CodingTargetSnapshotId snapshotId, long revision, TaskFactHash snapshotHash) {

    public CodingTargetSnapshotReference {
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        snapshotHash = Objects.requireNonNull(snapshotHash, "snapshotHash");
    }
}
