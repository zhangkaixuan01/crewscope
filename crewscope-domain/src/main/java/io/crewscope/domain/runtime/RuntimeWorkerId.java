package io.crewscope.domain.runtime;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable database identity of one execution Worker instance. */
public record RuntimeWorkerId(UUID value) implements AggregateId {

    public RuntimeWorkerId {
        value = AggregateId.requireValue(value, "RuntimeWorkerId");
    }

    public static RuntimeWorkerId generate() {
        return new RuntimeWorkerId(AggregateId.generateValue());
    }

    public static RuntimeWorkerId from(String value) {
        return new RuntimeWorkerId(AggregateId.parseCanonical(value, "RuntimeWorkerId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
