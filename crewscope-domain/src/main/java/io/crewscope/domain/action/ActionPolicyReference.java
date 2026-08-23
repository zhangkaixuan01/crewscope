package io.crewscope.domain.action;

import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Exact immutable policy revision applied to the source delivery. */
public record ActionPolicyReference(
        PolicySnapshotId id, long revision, TaskFactHash snapshotHash) {

    public ActionPolicyReference {
        id = Objects.requireNonNull(id, "id");
        if (revision < 1) {
            throw new IllegalArgumentException("PolicySnapshot revision must be positive");
        }
        snapshotHash = Objects.requireNonNull(snapshotHash, "snapshotHash");
    }
}
