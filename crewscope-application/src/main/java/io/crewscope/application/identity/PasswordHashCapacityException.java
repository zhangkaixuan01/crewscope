package io.crewscope.application.identity;

/** Safe overload signal mapped by the future HTTP boundary to 429 and Retry-After: 1. */
public final class PasswordHashCapacityException extends RuntimeException {

    public PasswordHashCapacityException() {
        super("Password verification capacity is temporarily unavailable", null, false, false);
    }
}
