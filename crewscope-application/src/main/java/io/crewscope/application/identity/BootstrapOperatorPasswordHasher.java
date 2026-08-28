package io.crewscope.application.identity;

import io.crewscope.domain.identity.LocalPasswordHash;

/** Trusted one-shot password boundary used only by deployment Operator provisioning. */
public interface BootstrapOperatorPasswordHasher {

    /** Validates policy and compares the external Secret with one approved persisted Hash. */
    BootstrapOperatorPasswordVerification verify(
            String rawPassword, LocalPasswordHash persistedHash);

    /** Validates policy and creates the current Argon2id representation for persistence. */
    LocalPasswordHash encode(String rawPassword);
}
