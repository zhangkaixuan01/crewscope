package io.crewscope.application.identity;

/** Stable internal reasons that the HTTP adapter folds into non-enumerating public responses. */
public enum LocalAccountRegistrationFailure {
    REGISTRATION_DISABLED,
    INVITATION_REQUIRED,
    INVITATION_INVALID,
    REGISTRATION_CONFLICT,
    REPLAY_AUTHENTICATION_FAILED,
    REGISTRATION_UNAVAILABLE
}
