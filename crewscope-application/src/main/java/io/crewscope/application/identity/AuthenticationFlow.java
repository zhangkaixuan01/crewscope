package io.crewscope.application.identity;

/** Closed anonymous entry points that consume independent authentication-defense windows. */
public enum AuthenticationFlow {
    LOGIN("login"),
    REGISTRATION("registration");

    private final String keySegment;

    AuthenticationFlow(String keySegment) {
        this.keySegment = keySegment;
    }

    public String keySegment() {
        return keySegment;
    }
}
