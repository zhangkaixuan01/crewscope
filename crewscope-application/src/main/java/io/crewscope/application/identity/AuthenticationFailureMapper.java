package io.crewscope.application.identity;

import io.crewscope.domain.identity.AuthenticationFailureReason;
import io.crewscope.domain.identity.LoginAttemptPolicy;
import java.util.Objects;

/** Collapses sensitive authentication reasons into the two approved anonymous outcomes. */
public final class AuthenticationFailureMapper {

    private AuthenticationFailureMapper() {}

    public static SafeAuthenticationFailure toSafeFailure(
            AuthenticationFailureReason reason, LoginAttemptPolicy policy) {
        AuthenticationFailureReason requiredReason = Objects.requireNonNull(reason, "reason");
        LoginAttemptPolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        if (requiredReason.isCapacityFailure()) {
            return SafeAuthenticationFailure.tooManyRequests(requiredPolicy.retryAfter());
        }
        return SafeAuthenticationFailure.invalidCredentials();
    }
}
