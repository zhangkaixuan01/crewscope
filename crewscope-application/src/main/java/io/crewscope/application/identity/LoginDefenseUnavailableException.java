package io.crewscope.application.identity;

/** Safe fail-closed signal for unavailable, malformed or time-inconsistent defense state. */
public final class LoginDefenseUnavailableException extends RuntimeException {

    public LoginDefenseUnavailableException() {
        super("Authentication defense is temporarily unavailable", null, false, false);
    }
}
