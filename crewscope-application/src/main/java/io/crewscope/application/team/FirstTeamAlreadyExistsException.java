package io.crewscope.application.team;

/** Signals that the current member has already completed first-Team onboarding. */
public final class FirstTeamAlreadyExistsException extends RuntimeException {

    public FirstTeamAlreadyExistsException() {
        super("First-Team onboarding has already been completed");
    }
}
