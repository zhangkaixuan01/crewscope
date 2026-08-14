package io.crewscope.domain.runtime;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identity of one Organization and environment scoped runtime registration. */
public record ExecutionRuntimeId(UUID value) implements AggregateId {

    public ExecutionRuntimeId {
        value = AggregateId.requireValue(value, "ExecutionRuntimeId");
    }

    public static ExecutionRuntimeId generate() {
        return new ExecutionRuntimeId(AggregateId.generateValue());
    }

    public static ExecutionRuntimeId from(String value) {
        return new ExecutionRuntimeId(AggregateId.parseCanonical(value, "ExecutionRuntimeId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
