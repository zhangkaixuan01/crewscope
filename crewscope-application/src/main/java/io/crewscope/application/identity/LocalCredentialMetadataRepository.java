package io.crewscope.application.identity;

import io.crewscope.domain.identity.LocalCredentialConflictException;
import io.crewscope.domain.identity.LocalCredentialId;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import java.util.Optional;

/** Non-secret metadata port; password hashes are deliberately absent from every method. */
public interface LocalCredentialMetadataRepository {

    Optional<LocalCredentialMetadata> findById(LocalCredentialId credentialId);

    Optional<LocalCredentialMetadata> findByAccountId(UserAccountId accountId);

    LocalCredentialMetadata create(LocalCredentialMetadata metadata)
            throws LocalCredentialConflictException;

    LocalCredentialMetadata update(LocalCredentialMetadata metadata, long expectedVersion)
            throws LocalCredentialConflictException, OptimisticLockConflictException;
}
