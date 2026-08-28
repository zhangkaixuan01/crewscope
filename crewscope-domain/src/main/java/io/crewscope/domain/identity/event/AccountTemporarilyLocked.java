package io.crewscope.domain.identity.event;

import io.crewscope.domain.shared.DomainEvent;

/** Version 1 temporary login lock fact expressed only as bounded policy evidence. */
public record AccountTemporarilyLocked(int failureCount, int lockDurationSeconds)
        implements DomainEvent {

    public AccountTemporarilyLocked {
        if (failureCount < 1) {
            throw new IllegalArgumentException("failureCount must be positive");
        }
        if (lockDurationSeconds < 1) {
            throw new IllegalArgumentException("lockDurationSeconds must be positive");
        }
    }
}
