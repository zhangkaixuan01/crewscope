package io.crewscope.domain.coding;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one immutable CodingTargetSnapshot revision. */
public record CodingTargetSnapshotId(UUID value) implements AggregateId {

    public CodingTargetSnapshotId {
        value = AggregateId.requireValue(value, "CodingTargetSnapshotId");
    }

    public static CodingTargetSnapshotId generate() {
        return new CodingTargetSnapshotId(AggregateId.generateValue());
    }

    public static CodingTargetSnapshotId from(String value) {
        return new CodingTargetSnapshotId(
                AggregateId.parseCanonical(value, "CodingTargetSnapshotId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
