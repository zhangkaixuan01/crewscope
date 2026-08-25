package io.crewscope.domain.inbox;

/** Bounded urgency used for deterministic Inbox ordering and notification policy. */
public enum InboxPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}
