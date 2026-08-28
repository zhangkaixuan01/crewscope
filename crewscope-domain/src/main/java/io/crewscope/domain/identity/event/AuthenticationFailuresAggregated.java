package io.crewscope.domain.identity.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;

/** Version 1 bounded authentication failure aggregate without an identifier, address or raw reason. */
public record AuthenticationFailuresAggregated(
        AuthenticationFailureClass failureClass,
        int occurrenceCount,
        int aggregationWindowSeconds) implements DomainEvent {

    public AuthenticationFailuresAggregated {
        failureClass = Objects.requireNonNull(failureClass, "failureClass");
        if (occurrenceCount < 1) {
            throw new IllegalArgumentException("occurrenceCount must be positive");
        }
        if (aggregationWindowSeconds < 1) {
            throw new IllegalArgumentException("aggregationWindowSeconds must be positive");
        }
    }
}
