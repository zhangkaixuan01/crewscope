package io.crewscope.domain.coding;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one TaskExecution-scoped managed Coding workspace. */
public record ExecutionWorkspaceId(UUID value) implements AggregateId {

    public ExecutionWorkspaceId {
        value = AggregateId.requireValue(value, "ExecutionWorkspaceId");
    }

    public static ExecutionWorkspaceId generate() {
        return new ExecutionWorkspaceId(AggregateId.generateValue());
    }

    public static ExecutionWorkspaceId from(String value) {
        return new ExecutionWorkspaceId(
                AggregateId.parseCanonical(value, "ExecutionWorkspaceId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
