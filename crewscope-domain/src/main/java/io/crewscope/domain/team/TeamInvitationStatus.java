package io.crewscope.domain.team;

/** One-time Team invitation lifecycle. */
public enum TeamInvitationStatus {
    PENDING,
    ACCEPTED,
    REVOKED,
    EXPIRED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
