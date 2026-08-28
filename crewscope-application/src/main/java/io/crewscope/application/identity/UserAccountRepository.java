package io.crewscope.application.identity;

import io.crewscope.domain.identity.AccountIdentifierConflictException;
import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.identity.Username;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import java.util.Optional;

/** Persistence port for account profile, normalized identifiers and optimistic versions. */
public interface UserAccountRepository {

    Optional<UserAccount> findById(UserAccountId accountId);

    /** Internal authentication lookup; callers must not expose hit/miss distinctions. */
    Optional<UserAccount> findByUsername(Username username);

    /** Internal authentication lookup; callers must not expose hit/miss distinctions. */
    Optional<UserAccount> findByEmail(NormalizedEmail email);

    /** Maps either normalized unique-key violation to the same non-enumerating conflict. */
    UserAccount create(UserAccount account) throws AccountIdentifierConflictException;

    UserAccount update(UserAccount account, long expectedVersion)
            throws AccountIdentifierConflictException, OptimisticLockConflictException;
}
