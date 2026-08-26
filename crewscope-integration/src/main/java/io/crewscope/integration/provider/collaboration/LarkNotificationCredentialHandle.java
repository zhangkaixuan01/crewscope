package io.crewscope.integration.provider.collaboration;

import io.crewscope.application.notification.NotificationCredentialHandle;
import io.crewscope.application.notification.NotificationCredentialOperation;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Claimed-action credential capability that revalidates Lark authorization on every operation. */
final class LarkNotificationCredentialHandle implements NotificationCredentialHandle {

    @FunctionalInterface
    interface AuthorizedOperation<T> {
        T apply(AuthorizedLarkAccess access);
    }

    private final LarkApiCallContext context;
    private final LarkCredentialAccessManager accessManager;
    private final TimeProvider timeProvider;
    private final UtcTimestamp expiresAt;
    private final AtomicBoolean closed = new AtomicBoolean();

    LarkNotificationCredentialHandle(
            LarkApiCallContext context,
            LarkCredentialAccessManager accessManager,
            TimeProvider timeProvider,
            UtcTimestamp expiresAt) {
        this.context = Objects.requireNonNull(context, "context");
        this.accessManager = Objects.requireNonNull(accessManager, "accessManager");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (expiresAt.compareTo(timeProvider.now()) <= 0) {
            throw new IllegalArgumentException(
                    "Lark notification credential expiry must be in the future");
        }
    }

    LarkConnectionAuthorization authorization() {
        return context.authorization();
    }

    <T> T withAuthorizedAccess(
            String purpose,
            Optional<ProviderCapabilities> requiredCapabilities,
            AuthorizedOperation<T> operation) {
        requireUsable();
        Duration remaining = Duration.between(timeProvider.now().value(), expiresAt.value());
        try (AuthorizedLarkAccess access = accessManager.authorize(
                context, purpose, requiredCapabilities, remaining)) {
            return Objects.requireNonNull(operation, "operation").apply(access);
        }
    }

    @Override
    public <T> T useSecret(NotificationCredentialOperation<T> operation) {
        return withAuthorizedAccess(
                "lark.notification.claim",
                Optional.of(LarkCollaborationCapabilities.NOTIFICATION_DELIVERY),
                access -> access.credentialHandle().useSecret(
                        bytes -> Objects.requireNonNull(operation, "operation").apply(bytes)));
    }

    @Override
    public UtcTimestamp expiresAt() {
        return expiresAt;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        closed.set(true);
    }

    private void requireUsable() {
        if (closed.get() || timeProvider.now().compareTo(expiresAt) >= 0) {
            close();
            throw LarkProviderException.of(
                    LarkProviderErrorCode.CREDENTIAL_UNAVAILABLE,
                    "Lark notification credential handle is unavailable",
                    "LARK_NOTIFICATION_CREDENTIAL_UNAVAILABLE");
        }
    }

    @Override
    public String toString() {
        return "LarkNotificationCredentialHandle[authorization=REDACTED, credential=REDACTED]";
    }
}
