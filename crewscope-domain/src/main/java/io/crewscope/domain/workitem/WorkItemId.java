package io.crewscope.domain.workitem;

import java.util.Objects;
import java.util.UUID;

public record WorkItemId(UUID value) {

    public WorkItemId {
        Objects.requireNonNull(value, "value");
    }

    public static WorkItemId generate() {
        return new WorkItemId(UUID.randomUUID());
    }
}
