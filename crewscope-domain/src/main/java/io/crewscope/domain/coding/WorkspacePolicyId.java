package io.crewscope.domain.coding;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of the immutable policy for one Coding execution attempt. */
public record WorkspacePolicyId(UUID value) implements AggregateId {

    public WorkspacePolicyId {
        value = AggregateId.requireValue(value, "WorkspacePolicyId");
    }

    public static WorkspacePolicyId generate() {
        return new WorkspacePolicyId(AggregateId.generateValue());
    }

    public static WorkspacePolicyId from(String value) {
        return new WorkspacePolicyId(AggregateId.parseCanonical(value, "WorkspacePolicyId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
