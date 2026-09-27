package io.crewscope.domain.inbox;

import java.util.Set;

/**
 * Member-owned Inbox disposition; UNREAD is derived when no authority row exists, and a persisted
 * UNREAD row carries the same effective status at a positive version (contract §5.1).
 */
public enum InboxDispositionStatus {
    UNREAD,
    READ,
    ACTED,
    ARCHIVED;

    /**
     * The reversible transition matrix of contract §5.1. Marking read or acted only moves forward
     * except back to UNREAD; restoring an archive lands on READ; archiving keeps history. The
     * archive-to-UNREAD path is deliberately absent — it must be applied as the explicit
     * restore-then-unmark sequence instead of a hidden restore.
     */
    public boolean canTransitionTo(InboxDispositionStatus target) {
        return transitions().contains(java.util.Objects.requireNonNull(target, "target"));
    }

    private Set<InboxDispositionStatus> transitions() {
        return switch (this) {
            case UNREAD -> Set.of(READ, ACTED, ARCHIVED);
            case READ -> Set.of(ACTED, ARCHIVED, UNREAD);
            case ACTED -> Set.of(ARCHIVED, UNREAD);
            case ARCHIVED -> Set.of(READ);
        };
    }
}
