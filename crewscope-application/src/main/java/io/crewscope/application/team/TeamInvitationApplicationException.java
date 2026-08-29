package io.crewscope.application.team;

import java.util.Objects;

/** Safe application error for token acceptance and management lifecycle conflicts. */
public final class TeamInvitationApplicationException extends RuntimeException {

    private final TeamInvitationApplicationFailure failure;

    public TeamInvitationApplicationException(TeamInvitationApplicationFailure failure) {
        super(message(failure));
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public TeamInvitationApplicationFailure failure() {
        return failure;
    }

    private static String message(TeamInvitationApplicationFailure failure) {
        return switch (Objects.requireNonNull(failure, "failure")) {
            case INVALID_INVITATION -> "Invitation is invalid or unavailable";
            case INVITATION_NOT_PENDING -> "Invitation is no longer pending";
        };
    }
}
