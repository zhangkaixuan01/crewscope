package io.crewscope.application.identity;

import java.util.Objects;

/** Constant federated-login failure surface; never carries subject or claim values. */
public final class OidcAuthenticationException extends RuntimeException {

    public enum Failure {
        /** The linked identity or its account, binding or principal is not usable. */
        DENIED,
        /** The known account is temporarily locked by the authentication defense. */
        LOCKED,
        /** No identity exists and the deployment admits no SSO first logins. */
        REGISTRATION_BLOCKED,
        /** The deterministic derivations cannot produce a unique account. */
        UNAVAILABLE
    }

    private final Failure failure;

    public OidcAuthenticationException(Failure failure) {
        super("OIDC authentication failed: " + Objects.requireNonNull(failure, "failure"));
        this.failure = failure;
    }

    public Failure failure() {
        return failure;
    }
}
