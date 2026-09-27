package io.crewscope.application.inbox;

import io.crewscope.domain.inbox.InboxDisposition;
import io.crewscope.domain.inbox.InboxDispositionStatus;

/**
 * Effective result of a disposition command. Version 0 only ever means "no authority row", which
 * already is UNREAD; every persisted transition — including back to UNREAD — reports its positive
 * committed version so receipts and ETags stay exact.
 */
public record InboxDispositionOutcome(InboxDispositionStatus status, long version) {

    public InboxDispositionOutcome {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (version == 0 && status != InboxDispositionStatus.UNREAD) {
            throw new IllegalArgumentException(
                    "version 0 only represents a missing UNREAD row");
        }
    }

    static InboxDispositionOutcome of(InboxDisposition disposition) {
        return new InboxDispositionOutcome(disposition.status(), disposition.version());
    }

    static InboxDispositionOutcome missingRowUnread() {
        return new InboxDispositionOutcome(InboxDispositionStatus.UNREAD, 0);
    }
}
