package io.crewscope.application.team;

/** Stable public failure classes that do not reveal invitation bearer or target identity. */
public enum TeamInvitationApplicationFailure {
    INVALID_INVITATION,
    INVITATION_NOT_PENDING
}
