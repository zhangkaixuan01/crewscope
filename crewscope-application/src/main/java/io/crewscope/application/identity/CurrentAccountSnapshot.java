package io.crewscope.application.identity;

import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete non-secret persistence snapshot used to restore the current authenticated account. */
public record CurrentAccountSnapshot(
        UserAccount account,
        List<LoginIdentity> loginIdentities,
        Optional<LocalCredentialMetadata> credentialMetadata,
        List<AccountOrganizationBinding> organizationBindings) {

    public CurrentAccountSnapshot {
        account = Objects.requireNonNull(account, "account");
        List<LoginIdentity> requiredIdentities =
                List.copyOf(Objects.requireNonNull(loginIdentities, "loginIdentities"));
        Optional<LocalCredentialMetadata> requiredCredential =
                Objects.requireNonNull(credentialMetadata, "credentialMetadata");
        List<AccountOrganizationBinding> requiredBindings = List.copyOf(
                Objects.requireNonNull(organizationBindings, "organizationBindings"));

        UserAccountId accountId = account.id();
        if (requiredIdentities.stream()
                .anyMatch(identity -> !identity.accountId().equals(accountId))) {
            throw new IllegalArgumentException(
                    "loginIdentities must belong to the snapshot account");
        }
        if (requiredCredential
                .filter(credential -> !credential.accountId().equals(accountId))
                .isPresent()) {
            throw new IllegalArgumentException(
                    "credentialMetadata must belong to the snapshot account");
        }
        if (requiredBindings.stream()
                .anyMatch(binding -> !binding.accountId().equals(accountId))) {
            throw new IllegalArgumentException(
                    "organizationBindings must belong to the snapshot account");
        }

        loginIdentities = requiredIdentities;
        credentialMetadata = requiredCredential;
        organizationBindings = requiredBindings;
    }
}
