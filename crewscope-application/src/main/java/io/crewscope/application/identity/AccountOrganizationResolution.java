package io.crewscope.application.identity;

import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.UserAccount;
import java.util.Objects;

/** Internally authenticated identity chain ready to act inside one Organization. */
public record AccountOrganizationResolution(
        UserAccount account,
        LoginIdentity loginIdentity,
        AccountOrganizationBinding binding,
        Principal principal) {

    public AccountOrganizationResolution {
        account = Objects.requireNonNull(account, "account");
        loginIdentity = Objects.requireNonNull(loginIdentity, "loginIdentity");
        binding = Objects.requireNonNull(binding, "binding");
        principal = Objects.requireNonNull(principal, "principal");
        if (!account.canAuthenticate()
                || !loginIdentity.isUsable()
                || !account.id().equals(loginIdentity.accountId())
                || !account.id().equals(binding.accountId())
                || !binding.isUsable()
                || !binding.isCompatibleWith(principal)) {
            throw new IllegalArgumentException("resolution identity chain must be consistent");
        }
    }
}
