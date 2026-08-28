package io.crewscope.domain.identity;

import java.util.Objects;

/** Per-account provider uniqueness coordinate. */
public record AccountIdentityProviderKey(
        UserAccountId accountId, IdentityProviderKey provider) {

    public AccountIdentityProviderKey {
        accountId = Objects.requireNonNull(accountId, "accountId");
        provider = Objects.requireNonNull(provider, "provider");
    }
}
