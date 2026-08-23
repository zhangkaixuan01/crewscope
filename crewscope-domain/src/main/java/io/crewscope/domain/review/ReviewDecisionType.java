package io.crewscope.domain.review;

/** Append-only human Review conclusion for one exact current ReviewRequest. */
public enum ReviewDecisionType {
    COMMENTED,
    APPROVED,
    CHANGES_REQUESTED,
    REJECTED;

    public boolean isTerminalGate() {
        return this != COMMENTED;
    }
}
