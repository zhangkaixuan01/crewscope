package io.crewscope.application.identity;

import io.crewscope.domain.identity.LocalCredentialId;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.UserAccountId;
import java.util.Optional;

/**
 * Read-only non-secret credential port.
 *
 * <p>Credential creation and rotation belong to the trusted hash store because PostgreSQL must
 * commit metadata and the encoded hash atomically. This port can never fabricate that secret
 * input or write an incomplete credential row.
 */
public interface LocalCredentialMetadataRepository {

    Optional<LocalCredentialMetadata> findById(LocalCredentialId credentialId);

    Optional<LocalCredentialMetadata> findByAccountId(UserAccountId accountId);

    /** Locks metadata through the restricted projection for a trusted outer transaction. */
    Optional<LocalCredentialMetadata> findByAccountIdForUpdate(UserAccountId accountId);
}
