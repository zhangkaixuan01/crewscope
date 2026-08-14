package io.crewscope.domain.task;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identity of one TaskExecution ownership lease. */
public record ExecutionLeaseId(UUID value) implements AggregateId {

    public ExecutionLeaseId {
        value = AggregateId.requireValue(value, "ExecutionLeaseId");
    }

    public static ExecutionLeaseId generate() {
        return new ExecutionLeaseId(AggregateId.generateValue());
    }

    public static ExecutionLeaseId from(String value) {
        return new ExecutionLeaseId(AggregateId.parseCanonical(value, "ExecutionLeaseId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
