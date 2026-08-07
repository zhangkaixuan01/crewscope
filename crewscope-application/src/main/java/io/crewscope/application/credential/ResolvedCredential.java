package io.crewscope.application.credential;

import java.util.Objects;

/** Authorized short-lived plaintext handle returned only to a trusted Connector Worker. */
public record ResolvedCredential(CredentialDescriptor descriptor, CredentialSecret secret)
        implements AutoCloseable {

    public ResolvedCredential {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(secret, "secret");
    }

    @Override
    public void close() {
        secret.close();
    }

    @Override
    public String toString() {
        return "ResolvedCredential[credentialId=" + descriptor.credentialId() + ", secret=REDACTED]";
    }
}
