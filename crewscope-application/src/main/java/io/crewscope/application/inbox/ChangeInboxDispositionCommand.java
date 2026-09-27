package io.crewscope.application.inbox;

import io.crewscope.domain.inbox.InboxDispositionStatus;
import java.util.Objects;

/**
 * Strong-ETag member command for READ, ACTED, ARCHIVED or a persisted UNREAD. UNREAD on a missing
 * row is the application layer's idempotent no-op (version 0); UNREAD on an archived row is applied
 * as the explicit restore-then-unmark sequence (contract §5.1).
 */
public record ChangeInboxDispositionCommand(
        InboxDispositionStatus targetStatus, long expectedVersion) {

    public ChangeInboxDispositionCommand {
        targetStatus = Objects.requireNonNull(targetStatus, "targetStatus");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }
}
