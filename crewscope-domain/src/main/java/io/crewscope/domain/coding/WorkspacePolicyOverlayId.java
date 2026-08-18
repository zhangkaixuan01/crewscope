package io.crewscope.domain.coding;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one monotonic WorkspacePolicy overlay lineage. */
public record WorkspacePolicyOverlayId(UUID value) implements AggregateId {

    public WorkspacePolicyOverlayId {
        value = AggregateId.requireValue(value, "WorkspacePolicyOverlayId");
    }

    public static WorkspacePolicyOverlayId generate() {
        return new WorkspacePolicyOverlayId(AggregateId.generateValue());
    }

    public static WorkspacePolicyOverlayId from(String value) {
        return new WorkspacePolicyOverlayId(
                AggregateId.parseCanonical(value, "WorkspacePolicyOverlayId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
