package io.crewscope.application.inbox;

import io.crewscope.domain.inbox.InboxDispositionStatus;
import java.util.Objects;

/** Strong-ETag member command for READ, ACTED or ARCHIVED. */
public record ChangeInboxDispositionCommand(
        InboxDispositionStatus targetStatus, long expectedVersion) {

    public ChangeInboxDispositionCommand {
        targetStatus = Objects.requireNonNull(targetStatus, "targetStatus");
        if (targetStatus == InboxDispositionStatus.UNREAD) {
            throw new IllegalArgumentException("UNREAD cannot be submitted as a disposition command");
        }
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }
}
