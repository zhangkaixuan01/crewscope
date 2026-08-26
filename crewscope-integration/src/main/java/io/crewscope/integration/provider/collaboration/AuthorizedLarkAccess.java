package io.crewscope.integration.provider.collaboration;

import java.util.Objects;

/** Revalidated authorization plus one short-lived CredentialStore handle. */
record AuthorizedLarkAccess(
        LarkTokenCacheKey cacheKey,
        LarkCredentialHandle credentialHandle) implements AutoCloseable {

    AuthorizedLarkAccess {
        cacheKey = Objects.requireNonNull(cacheKey, "cacheKey");
        credentialHandle = Objects.requireNonNull(credentialHandle, "credentialHandle");
    }

    @Override
    public void close() {
        credentialHandle.close();
    }

    @Override
    public String toString() {
        return "AuthorizedLarkAccess[authorization=REDACTED, credential=REDACTED]";
    }
}
