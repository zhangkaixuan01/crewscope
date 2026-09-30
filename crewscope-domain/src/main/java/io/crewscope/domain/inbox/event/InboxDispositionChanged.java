package io.crewscope.domain.inbox.event;

import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Member-local Inbox disposition command fact: the committed status and its exact version. */
public record InboxDispositionChanged(UUID inboxItemId, String status, long version)
        implements DomainEvent {

    public InboxDispositionChanged {
        inboxItemId = Objects.requireNonNull(inboxItemId, "inboxItemId");
        status = requireStatus(status);
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    private static String requireStatus(String value) {
        String required = Objects.requireNonNull(value, "status").strip();
        if (required.isEmpty() || required.length() > 16) {
            throw new IllegalArgumentException("status must contain between 1 and 16 characters");
        }
        return required;
    }
}
