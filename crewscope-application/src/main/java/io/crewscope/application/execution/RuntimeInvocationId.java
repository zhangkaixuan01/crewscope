package io.crewscope.application.execution;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Logical Conversation invocation retained across an interrupt and one or more resume segments. */
public record RuntimeInvocationId(UUID value) implements AggregateId {

    public RuntimeInvocationId {
        value = AggregateId.requireValue(value, "RuntimeInvocationId");
    }

    public static RuntimeInvocationId generate() {
        return new RuntimeInvocationId(AggregateId.generateValue());
    }

    public static RuntimeInvocationId from(String value) {
        return new RuntimeInvocationId(
                AggregateId.parseCanonical(value, "RuntimeInvocationId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
