package io.crewscope.domain.inbox;

/** Member-owned monotonic disposition; UNREAD is derived when no authority row exists. */
public enum InboxDispositionStatus {
    UNREAD,
    READ,
    ACTED,
    ARCHIVED;

    public boolean isAfter(InboxDispositionStatus other) {
        return ordinal() > java.util.Objects.requireNonNull(other, "other").ordinal();
    }
}
