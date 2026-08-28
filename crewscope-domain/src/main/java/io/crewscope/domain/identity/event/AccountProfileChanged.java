package io.crewscope.domain.identity.event;

import io.crewscope.domain.shared.DomainEvent;

/** Version 1 profile mutation fact that records changed coordinates without their submitted values. */
public record AccountProfileChanged(
        boolean usernameChanged, boolean emailChanged, boolean displayNameChanged)
        implements DomainEvent {

    public AccountProfileChanged {
        if (!usernameChanged && !emailChanged && !displayNameChanged) {
            throw new IllegalArgumentException("At least one profile coordinate must change");
        }
    }
}
