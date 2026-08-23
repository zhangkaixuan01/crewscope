package io.crewscope.infrastructure.github;

import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.ResolvedCredential;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Purpose-bound short-lived credential capability used only inside the GitHub adapter. */
public final class GitHubCredentialHandle implements AutoCloseable {

    @FunctionalInterface
    interface Resolver {
        ResolvedCredential resolve();
    }

    @FunctionalInterface
    public interface SecretOperation<T> {
        T apply(byte[] secret);
    }

    private final ConnectionId connectionId;
    private final long secretVersion;
    private final UtcTimestamp expiresAt;
    private final TimeProvider timeProvider;
    private final Resolver resolver;
    private final AtomicBoolean closed = new AtomicBoolean();

    GitHubCredentialHandle(
            ConnectionId connectionId,
            long secretVersion,
            UtcTimestamp issuedAt,
            Duration timeToLive,
            TimeProvider timeProvider,
            Resolver resolver) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        if (secretVersion < 0) {
            throw new IllegalArgumentException("secretVersion must not be negative");
        }
        this.secretVersion = secretVersion;
        UtcTimestamp issued = Objects.requireNonNull(issuedAt, "issuedAt");
        Duration ttl = Objects.requireNonNull(timeToLive, "timeToLive");
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("GitHub credential TTL must be within (0, 5m]");
        }
        this.expiresAt = UtcTimestamp.from(issued.value().plus(ttl));
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** Copies plaintext for one callback and clears every intermediate buffer afterwards. */
    public <T> T useSecret(SecretOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        requireUsable();
        try (ResolvedCredential resolved = resolver.resolve();
                CredentialSecret credential = resolved.secret()) {
            byte[] bytes = credential.copyBytes();
            try {
                return operation.apply(bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    public ConnectionId connectionId() {
        return connectionId;
    }

    public long secretVersion() {
        return secretVersion;
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
        return "GitHubCredentialHandle[connectionId=" + connectionId
                + ", secretVersion=" + secretVersion + ", secret=REDACTED]";
    }

    private void requireUsable() {
        if (closed.get()) {
            throw new IllegalStateException("GitHub credential handle is closed");
        }
        if (timeProvider.now().compareTo(expiresAt) >= 0) {
            close();
            throw new IllegalStateException("GitHub credential handle is expired");
        }
    }
}
