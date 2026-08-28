package io.crewscope.application.identity;

import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationKey;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;

/** Resolves only pre-existing bindings and therefore cannot provision from a requested URL scope. */
public final class AccountOrganizationPrincipalResolver {

    private final AccountOrganizationBindingRepository bindingRepository;
    private final OrganizationPrincipalReader principalReader;

    public AccountOrganizationPrincipalResolver(
            AccountOrganizationBindingRepository bindingRepository,
            OrganizationPrincipalReader principalReader) {
        this.bindingRepository = Objects.requireNonNull(bindingRepository, "bindingRepository");
        this.principalReader = Objects.requireNonNull(principalReader, "principalReader");
    }

    /**
     * Resolves the complete usable identity chain. Every rejected state collapses to an empty result
     * so callers cannot distinguish missing accounts, bindings or Principals.
     */
    public Optional<AccountOrganizationResolution> resolveExisting(
            UserAccount account,
            LoginIdentity loginIdentity,
            OrganizationId requestedOrganizationId) {
        UserAccount requiredAccount = Objects.requireNonNull(account, "account");
        LoginIdentity requiredIdentity = Objects.requireNonNull(loginIdentity, "loginIdentity");
        OrganizationId organizationId =
                Objects.requireNonNull(requestedOrganizationId, "requestedOrganizationId");
        if (!requiredAccount.canAuthenticate()
                || !requiredIdentity.isUsable()
                || !requiredIdentity.accountId().equals(requiredAccount.id())) {
            return Optional.empty();
        }

        Optional<AccountOrganizationBinding> binding =
                bindingRepository.findByAccountOrganizationKey(
                        new AccountOrganizationKey(requiredAccount.id(), organizationId));
        if (binding.isEmpty() || !binding.orElseThrow().isUsable()) {
            return Optional.empty();
        }

        AccountOrganizationBinding existingBinding = binding.orElseThrow();
        Optional<Principal> principal = principalReader.findById(
                organizationId, existingBinding.principalId());
        if (principal.isEmpty() || !existingBinding.isCompatibleWith(principal.orElseThrow())) {
            return Optional.empty();
        }
        return Optional.of(new AccountOrganizationResolution(
                requiredAccount,
                requiredIdentity,
                existingBinding,
                principal.orElseThrow()));
    }
}
