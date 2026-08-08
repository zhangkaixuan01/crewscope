package io.crewscope.domain.conversation;

/** Review lifecycle of one versioned TaskIntent proposal. */
public enum TaskIntentStatus {
    DRAFT,
    READY,
    CONFIRMED,
    REJECTED,
    EXPIRED;

    public boolean isTerminal() {
        return this == CONFIRMED || this == REJECTED || this == EXPIRED;
    }
}
