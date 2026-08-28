package io.crewscope.domain.identity;

import java.util.Objects;

/** Deployment-global provider/subject uniqueness coordinate. */
public record LoginIdentityKey(IdentityProviderKey provider, LoginIdentitySubject subject) {

    public LoginIdentityKey {
        provider = Objects.requireNonNull(provider, "provider");
        subject = Objects.requireNonNull(subject, "subject");
    }

    @Override
    public String toString() {
        return "LoginIdentityKey[provider=" + provider + ", subject=REDACTED]";
    }
}
