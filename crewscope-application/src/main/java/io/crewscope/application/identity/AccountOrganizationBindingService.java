package io.crewscope.application.identity;

import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingConflictException;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.AccountOrganizationKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Idempotently binds an Account to an existing compatible Principal without creating membership. */
public final class AccountOrganizationBindingService {

    private final AccountOrganizationBindingRepository bindingRepository;

    public AccountOrganizationBindingService(
            AccountOrganizationBindingRepository bindingRepository) {
        this.bindingRepository = Objects.requireNonNull(bindingRepository, "bindingRepository");
    }

    /**
     * Converges concurrent attempts through repository uniqueness. A different Principal at the
     * Account/Organization coordinate remains a safe conflict.
     */
    public AccountOrganizationBinding bindExistingPrincipal(
            AccountOrganizationBindingId bindingId,
            UserAccount account,
            OrganizationId organizationId,
            Principal principal,
            UtcTimestamp occurredAt) {
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(occurredAt, "occurredAt");
        AccountOrganizationKey key = new AccountOrganizationKey(account.id(), organizationId);
        Optional<AccountOrganizationBinding> existing =
                bindingRepository.findByAccountOrganizationKey(key);
        if (existing.isPresent()) {
            return requireSamePrincipal(existing.orElseThrow(), principal);
        }

        AccountOrganizationBinding candidate = AccountOrganizationBinding.bind(
                bindingId, account, organizationId, principal, occurredAt);
        try {
            return bindingRepository.create(candidate);
        } catch (AccountOrganizationBindingConflictException conflict) {
            // A concurrent identical writer may have committed after the first lookup.
            return bindingRepository
                    .findByAccountOrganizationKey(key)
                    .map(binding -> requireSamePrincipal(binding, principal))
                    .orElseThrow(() -> conflict);
        }
    }

    private static AccountOrganizationBinding requireSamePrincipal(
            AccountOrganizationBinding binding, Principal principal) {
        if (!binding.isUsable()
                || !binding.principalId().equals(principal.id())
                || !binding.isCompatibleWith(principal)) {
            throw new AccountOrganizationBindingConflictException();
        }
        return binding;
    }
}
