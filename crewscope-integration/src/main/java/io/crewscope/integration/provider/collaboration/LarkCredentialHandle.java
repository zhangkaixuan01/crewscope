package io.crewscope.integration.provider.collaboration;

import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.ResolvedCredential;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Purpose-bound CredentialStore capability whose plaintext is scoped to one callback. */
final class LarkCredentialHandle implements AutoCloseable {

    @FunctionalInterface
    interface Resolver {
        ResolvedCredential resolve();
    }

    @FunctionalInterface
    interface SecretOperation<T> {
        T apply(byte[] secret);
    }

    private final ConnectionId connectionId;
    private final long credentialVersion;
    private final long secretVersion;
    private final UtcTimestamp expiresAt;
    private final TimeProvider timeProvider;
    private final Resolver resolver;
    private final AtomicBoolean closed = new AtomicBoolean();

    LarkCredentialHandle(
            ConnectionId connectionId,
            long credentialVersion,
            long secretVersion,
            UtcTimestamp issuedAt,
            Duration timeToLive,
            TimeProvider timeProvider,
            Resolver resolver) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        if (credentialVersion < 0 || secretVersion < 0) {
            throw new IllegalArgumentException("Lark credential versions must not be negative");
        }
        this.credentialVersion = credentialVersion;
        this.secretVersion = secretVersion;
        UtcTimestamp issued = Objects.requireNonNull(issuedAt, "issuedAt");
        Duration ttl = Objects.requireNonNull(timeToLive, "timeToLive");
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Lark credential TTL must be within (0, 5m]");
        }
        this.expiresAt = UtcTimestamp.from(issued.value().plus(ttl));
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** Clears the CredentialStore holder and every defensive byte copy after the callback. */
    <T> T useSecret(SecretOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        requireUsable();
        try (ResolvedCredential resolved = resolver.resolve()) {
            CredentialSecret credential = resolved.secret();
            byte[] bytes = credential.copyBytes();
            try {
                return operation.apply(bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    long credentialVersion() {
        return credentialVersion;
    }

    long secretVersion() {
        return secretVersion;
    }

    UtcTimestamp expiresAt() {
        return expiresAt;
    }

    boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        closed.set(true);
    }

    @Override
    public String toString() {
        return "LarkCredentialHandle[connectionId=" + connectionId
                + ", credentialVersion=" + credentialVersion
                + ", secretVersion=" + secretVersion + ", secret=REDACTED]";
    }

    private void requireUsable() {
        if (closed.get()) {
            throw new IllegalStateException("Lark credential handle is closed");
        }
        if (timeProvider.now().compareTo(expiresAt) >= 0) {
            close();
            throw new LarkProviderException(
                    LarkProviderErrorCode.CREDENTIAL_UNAVAILABLE,
                    "Lark credential handle expired before use",
                    java.util.Optional.empty(),
                    "LARK_CREDENTIAL_EXPIRED");
        }
    }
}
