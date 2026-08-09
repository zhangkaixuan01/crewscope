package io.crewscope.application.execution;

import java.util.Objects;
import java.util.UUID;

/** Logical Conversation invocation retained across an interrupt and one or more resume segments. */
public record RuntimeInvocationId(UUID value) {

    public RuntimeInvocationId {
        value = Objects.requireNonNull(value, "value");
    }

    public static RuntimeInvocationId generate() {
        return new RuntimeInvocationId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
