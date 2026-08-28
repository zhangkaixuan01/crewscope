package io.crewscope.domain.identity.event;

import io.crewscope.domain.identity.IdentityProviderKey;
import io.crewscope.domain.shared.DomainEvent;

/** Version 1 successful authentication fact with no session or credential identifier. */
public record AuthenticationSucceeded(
        String provider, boolean credentialUpgraded, long securityVersion) implements DomainEvent {

    public AuthenticationSucceeded {
        provider = new IdentityProviderKey(provider).value();
        if (securityVersion < 1) {
            throw new IllegalArgumentException("securityVersion must be positive");
        }
    }
}
