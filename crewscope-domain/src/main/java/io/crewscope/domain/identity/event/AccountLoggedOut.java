package io.crewscope.domain.identity.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;

/** Version 1 logout fact that records reach and security epoch, never a session identifier. */
public record AccountLoggedOut(AccountLogoutScope scope, long securityVersion)
        implements DomainEvent {

    public AccountLoggedOut {
        scope = Objects.requireNonNull(scope, "scope");
        if (securityVersion < 1) {
            throw new IllegalArgumentException("securityVersion must be positive");
        }
    }
}
