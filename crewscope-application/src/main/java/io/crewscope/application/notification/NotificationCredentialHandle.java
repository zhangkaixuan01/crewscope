package io.crewscope.application.notification;

import io.crewscope.domain.shared.time.UtcTimestamp;

/** Action-scoped, revocable credential capability; no token value is persistable or printable. */
public interface NotificationCredentialHandle extends AutoCloseable {

    /** Resolves plaintext only for this callback; implementations clear temporary copies. */
    <T> T useSecret(NotificationCredentialOperation<T> operation);

    UtcTimestamp expiresAt();

    boolean isClosed();

    @Override
    void close();
}
