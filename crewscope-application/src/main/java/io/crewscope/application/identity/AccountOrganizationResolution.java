package io.crewscope.application.identity;

import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import java.util.List;
import java.util.Objects;

/** Internally authenticated identity chain ready to act inside one Organization. */
public record AccountOrganizationResolution(
        UserAccount account,
        LoginIdentity loginIdentity,
        AccountOrganizationBinding binding,
        Principal principal,
        List<LoginIdentity> loginIdentities) {

    public AccountOrganizationResolution(
            UserAccount account,
            LoginIdentity loginIdentity,
            AccountOrganizationBinding binding,
            Principal principal) {
        this(account, loginIdentity, binding, principal, List.of(loginIdentity));
    }

    public AccountOrganizationResolution {
        account = Objects.requireNonNull(account, "account");
        loginIdentity = Objects.requireNonNull(loginIdentity, "loginIdentity");
        binding = Objects.requireNonNull(binding, "binding");
        principal = Objects.requireNonNull(principal, "principal");
        List<LoginIdentity> requiredIdentities = List.copyOf(
                Objects.requireNonNull(loginIdentities, "loginIdentities"));
        UserAccountId accountId = account.id();
        if (!account.canAuthenticate()
                || !loginIdentity.isUsable()
                || !accountId.equals(loginIdentity.accountId())
                || !accountId.equals(binding.accountId())
                || !binding.isUsable()
                || !binding.isCompatibleWith(principal)
                || requiredIdentities.isEmpty()
                || requiredIdentities.stream()
                        .anyMatch(identity -> !identity.accountId().equals(accountId))) {
            throw new IllegalArgumentException("resolution identity chain must be consistent");
        }
        loginIdentities = requiredIdentities;
    }

    /** Attaches the account's complete identity set for owner-facing projections (M12-I01). */
    public AccountOrganizationResolution withLoginIdentities(List<LoginIdentity> identities) {
        return new AccountOrganizationResolution(account, loginIdentity, binding, principal, identities);
    }
}

