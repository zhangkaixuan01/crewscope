package io.crewscope.domain.identity.event;

import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;

/** Version 1 account creation fact without username, email, display name or credential material. */
public record UserAccountRegistered(
        AccountRegistrationSource source, PlatformRole platformRole) implements DomainEvent {

    public UserAccountRegistered {
        source = Objects.requireNonNull(source, "source");
        platformRole = Objects.requireNonNull(platformRole, "platformRole");
        if ((source == AccountRegistrationSource.BOOTSTRAP)
                ^ (platformRole == PlatformRole.OPERATOR)) {
            throw new IllegalArgumentException(
                    "Only the trusted bootstrap source may register an OPERATOR account");
        }
    }
}
