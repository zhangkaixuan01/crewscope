package io.crewscope.application.model;

import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.ResolvedCredential;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Revocable short-lived capability that resolves plaintext only inside a bounded callback. */
public final class ProviderCredentialHandle implements AutoCloseable {

    @FunctionalInterface
    interface Resolver {
        ResolvedCredential resolve(ModelConnectionId connectionId, ModelCredentialVersion version);
    }

    private final ModelConnectionId connectionId;
    private final ModelCredentialVersion credentialVersion;
    private final UtcTimestamp issuedAt;
    private final UtcTimestamp expiresAt;
    private final TimeProvider timeProvider;
    private final Resolver resolver;
    private final AtomicBoolean closed = new AtomicBoolean();

    ProviderCredentialHandle(
            ModelConnectionId connectionId,
            ModelCredentialVersion credentialVersion,
            UtcTimestamp issuedAt,
            Duration timeToLive,
            TimeProvider timeProvider,
            Resolver resolver) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.credentialVersion = Objects.requireNonNull(credentialVersion, "credentialVersion");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        Duration ttl = Objects.requireNonNull(timeToLive, "timeToLive");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("timeToLive must be positive");
        }
        this.expiresAt = UtcTimestamp.from(issuedAt.value().plus(ttl));
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** Resolves, copies and clears the secret without exposing a persistent credential object. */
    public <T> T useSecret(ProviderCredentialOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        requireUsable();
        try (ResolvedCredential resolved = resolver.resolve(connectionId, credentialVersion);
                CredentialSecret secret = resolved.secret()) {
            byte[] bytes = secret.copyBytes();
            try {
                return operation.apply(bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    public ModelConnectionId connectionId() {
        return connectionId;
    }

    public ModelCredentialVersion credentialVersion() {
        return credentialVersion;
    }

    public UtcTimestamp issuedAt() {
        return issuedAt;
    }

    public UtcTimestamp expiresAt() {
        return expiresAt;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        closed.set(true);
    }

    @Override
    public String toString() {
        return "ProviderCredentialHandle[connectionId=" + connectionId
                + ", credentialVersion=" + credentialVersion + ", secret=REDACTED]";
    }

    private void requireUsable() {
        if (closed.get()) {
            throw new IllegalStateException("Provider credential handle is closed");
        }
        if (timeProvider.now().compareTo(expiresAt) >= 0) {
            close();
            throw new IllegalStateException("Provider credential handle is expired");
        }
    }
}
