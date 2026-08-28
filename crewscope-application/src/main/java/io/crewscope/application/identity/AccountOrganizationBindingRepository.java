package io.crewscope.application.identity;

import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingConflictException;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.AccountOrganizationKey;
import io.crewscope.domain.identity.OrganizationPrincipalKey;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import java.util.List;
import java.util.Optional;

/** Persistence port enforcing both Account/Organization and Organization/Principal coordinates. */
public interface AccountOrganizationBindingRepository {

    Optional<AccountOrganizationBinding> findById(AccountOrganizationBindingId bindingId);

    Optional<AccountOrganizationBinding> findByAccountOrganizationKey(
            AccountOrganizationKey key);

    Optional<AccountOrganizationBinding> findByOrganizationPrincipalKey(
            OrganizationPrincipalKey key);

    List<AccountOrganizationBinding> findByAccountId(UserAccountId accountId);

    /** Maps either unique-index violation to one non-enumerating conflict. */
    AccountOrganizationBinding create(AccountOrganizationBinding binding)
            throws AccountOrganizationBindingConflictException;

    AccountOrganizationBinding update(AccountOrganizationBinding binding, long expectedVersion)
            throws AccountOrganizationBindingConflictException, OptimisticLockConflictException;
}
