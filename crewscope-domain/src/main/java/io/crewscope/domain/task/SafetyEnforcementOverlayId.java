package io.crewscope.domain.task;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of a versioned real-time safety enforcement stream. */
public record SafetyEnforcementOverlayId(UUID value) implements AggregateId {
    public SafetyEnforcementOverlayId {
        value = AggregateId.requireValue(value, "SafetyEnforcementOverlayId");
    }

    public static SafetyEnforcementOverlayId generate() {
        return new SafetyEnforcementOverlayId(AggregateId.generateValue());
    }

    public static SafetyEnforcementOverlayId from(String value) {
        return new SafetyEnforcementOverlayId(
                AggregateId.parseCanonical(value, "SafetyEnforcementOverlayId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
