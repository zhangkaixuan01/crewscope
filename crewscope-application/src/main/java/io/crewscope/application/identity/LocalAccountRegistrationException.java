package io.crewscope.application.identity;

import java.util.Objects;

/** Secret-free registration failure that never carries persistence or token causes. */
public final class LocalAccountRegistrationException extends RuntimeException {

    private final LocalAccountRegistrationFailure failure;

    public LocalAccountRegistrationException(LocalAccountRegistrationFailure failure) {
        super(message(failure));
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public LocalAccountRegistrationFailure failure() {
        return failure;
    }

    private static String message(LocalAccountRegistrationFailure failure) {
        return switch (Objects.requireNonNull(failure, "failure")) {
            case REGISTRATION_DISABLED -> "Local account registration is disabled";
            case INVITATION_REQUIRED, INVITATION_INVALID ->
                    "A valid Team invitation is required";
            case REGISTRATION_CONFLICT -> "The account could not be registered";
            case REPLAY_AUTHENTICATION_FAILED ->
                    "The completed registration could not be authenticated";
            case REGISTRATION_UNAVAILABLE -> "Local account registration is unavailable";
        };
    }
}
