package io.crewscope.domain.task;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identifier for one immutable authorization snapshot. */
public record PolicySnapshotId(UUID value) implements AggregateId {
    public PolicySnapshotId {
        value = AggregateId.requireValue(value, "PolicySnapshotId");
    }

    public static PolicySnapshotId generate() {
        return new PolicySnapshotId(AggregateId.generateValue());
    }

    public static PolicySnapshotId from(String value) {
        return new PolicySnapshotId(AggregateId.parseCanonical(value, "PolicySnapshotId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
