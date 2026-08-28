package io.crewscope.application.identity;

import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.LocalPasswordHash;
import io.crewscope.domain.identity.UserAccountId;
import java.util.Optional;

/** Trusted persistence port that atomically keeps credential metadata and encoded Hash together. */
public interface LocalCredentialStore {

    LocalCredentialAuthenticationMaterial create(
            LocalCredentialMetadata metadata, LocalPasswordHash passwordHash);

    Optional<LocalCredentialAuthenticationMaterial> findByAccountIdForAuthentication(
            UserAccountId accountId);

    /** Replaces a Hash only if the credential still has the version that was authenticated. */
    boolean rotateIfUnchanged(
            LocalCredentialMetadata replacement,
            LocalPasswordHash replacementHash,
            long expectedMetadataVersion);
}
