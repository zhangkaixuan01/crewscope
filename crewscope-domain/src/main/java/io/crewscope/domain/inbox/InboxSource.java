package io.crewscope.domain.inbox;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Rebuildable fact describing whether one precise member source still needs handling. */
public record InboxSource(
        InboxSourceKey key,
        InboxPriority priority,
        Optional<UtcTimestamp> deadline,
        UtcTimestamp openedAt,
        InboxSourceStatus status,
        Optional<InboxCloseReason> closeReason,
        Optional<UtcTimestamp> closedAt) {

    public InboxSource {
        key = Objects.requireNonNull(key, "key");
        priority = Objects.requireNonNull(priority, "priority");
        deadline = Objects.requireNonNull(deadline, "deadline");
        openedAt = Objects.requireNonNull(openedAt, "openedAt");
        status = Objects.requireNonNull(status, "status");
        closeReason = Objects.requireNonNull(closeReason, "closeReason");
        closedAt = Objects.requireNonNull(closedAt, "closedAt");
        UtcTimestamp requiredOpenedAt = openedAt;
        if (deadline.filter(value -> value.compareTo(requiredOpenedAt) < 0).isPresent()) {
            throw new DomainValidationException(
                    "inboxSource.deadline", "must not be before openedAt");
        }
        requireTerminalShape(key.itemType(), openedAt, status, closeReason, closedAt);
    }

    public static InboxSource open(
            InboxSourceKey key,
            InboxPriority priority,
            Optional<UtcTimestamp> deadline,
            UtcTimestamp openedAt) {
        return new InboxSource(
                key,
                priority,
                deadline,
                openedAt,
                InboxSourceStatus.OPEN,
                Optional.empty(),
                Optional.empty());
    }

    /** Closes the replaceable source while retaining its stable identity and historical row. */
    public InboxSource close(InboxCloseReason reason, UtcTimestamp occurredAt) {
        InboxCloseReason requiredReason = Objects.requireNonNull(reason, "reason");
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        if (status == InboxSourceStatus.CLOSED) {
            if (closeReason.filter(requiredReason::equals).isPresent()
                    && closedAt.filter(requiredTime::equals).isPresent()) {
                return this;
            }
            throw new IllegalStateException("Closed Inbox source cannot be closed again");
        }
        return new InboxSource(
                key,
                priority,
                deadline,
                openedAt,
                InboxSourceStatus.CLOSED,
                Optional.of(requiredReason),
                Optional.of(requiredTime));
    }

    public boolean isOpen() {
        return status == InboxSourceStatus.OPEN;
    }

    private static void requireTerminalShape(
            InboxItemType itemType,
            UtcTimestamp openedAt,
            InboxSourceStatus status,
            Optional<InboxCloseReason> closeReason,
            Optional<UtcTimestamp> closedAt) {
        if (status == InboxSourceStatus.OPEN
                && (closeReason.isPresent() || closedAt.isPresent())) {
            throw new DomainValidationException(
                    "inboxSource.status", "OPEN source cannot contain terminal facts");
        }
        if (status == InboxSourceStatus.CLOSED
                && (closeReason.isEmpty() || closedAt.isEmpty())) {
            throw new DomainValidationException(
                    "inboxSource.status", "CLOSED source requires reason and timestamp");
        }
        if (closeReason.filter(reason -> !reason.supports(itemType)).isPresent()) {
            throw new DomainValidationException(
                    "inboxSource.closeReason", "is incompatible with the Inbox item type");
        }
        if (closedAt.filter(value -> value.compareTo(openedAt) < 0).isPresent()) {
            throw new DomainValidationException(
                    "inboxSource.closedAt", "must not be before openedAt");
        }
    }
}
