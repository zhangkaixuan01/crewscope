package io.crewscope.application.identity;

import io.crewscope.domain.identity.AccountIdentityProviderKey;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityConflictException;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.LoginIdentityKey;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import java.util.List;
import java.util.Optional;

/** Persistence port for provider identities and both mandatory uniqueness coordinates. */
public interface LoginIdentityRepository {

    Optional<LoginIdentity> findById(LoginIdentityId identityId);

    Optional<LoginIdentity> findByIdentityKey(LoginIdentityKey identityKey);

    Optional<LoginIdentity> findByAccountProviderKey(AccountIdentityProviderKey accountProviderKey);

    List<LoginIdentity> findByAccountId(UserAccountId accountId);

    /** Maps either unique-index violation to the same subject-safe conflict. */
    LoginIdentity create(LoginIdentity identity) throws LoginIdentityConflictException;

    LoginIdentity update(LoginIdentity identity, long expectedVersion)
            throws LoginIdentityConflictException, OptimisticLockConflictException;
}
