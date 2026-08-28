package io.crewscope.domain.identity.event;

import io.crewscope.domain.shared.DomainEvent;

/** Version 1 password rotation fact containing versions only, never password or hash material. */
public record AccountPasswordChanged(long credentialVersion, long securityVersion)
        implements DomainEvent {

    public AccountPasswordChanged {
        if (credentialVersion < 1) {
            throw new IllegalArgumentException("credentialVersion must be positive");
        }
        if (securityVersion < 1) {
            throw new IllegalArgumentException("securityVersion must be positive");
        }
    }
}
