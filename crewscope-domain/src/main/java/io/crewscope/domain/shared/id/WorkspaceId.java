package io.crewscope.domain.shared.id;

import java.util.UUID;

/** Strongly typed product Workspace aggregate identifier. */
public record WorkspaceId(UUID value) implements AggregateId {

    public WorkspaceId {
        value = AggregateId.requireValue(value, "WorkspaceId");
    }

    public static WorkspaceId generate() {
        return new WorkspaceId(AggregateId.generateValue());
    }

    public static WorkspaceId from(String value) {
        return new WorkspaceId(AggregateId.parseCanonical(value, "WorkspaceId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
