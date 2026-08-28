package io.crewscope.application.identity;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Anonymous-safe authentication failure without account, identifier or internal reason data. */
public record SafeAuthenticationFailure(
        AuthenticationFailureCode code, String message, Optional<Duration> retryAfter) {

    private static final SafeAuthenticationFailure INVALID_CREDENTIALS =
            new SafeAuthenticationFailure(
                    AuthenticationFailureCode.INVALID_CREDENTIALS,
                    "Invalid credentials",
                    Optional.empty());

    public SafeAuthenticationFailure {
        code = Objects.requireNonNull(code, "code");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        message = message.strip();
        String expectedMessage = switch (code) {
            case INVALID_CREDENTIALS -> "Invalid credentials";
            case TOO_MANY_REQUESTS -> "Too many requests";
        };
        if (!expectedMessage.equals(message)) {
            throw new IllegalArgumentException("message must match the fixed public code");
        }
        retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
        if (code == AuthenticationFailureCode.INVALID_CREDENTIALS && retryAfter.isPresent()) {
            throw new IllegalArgumentException("invalid_credentials must not include retryAfter");
        }
        if (code == AuthenticationFailureCode.TOO_MANY_REQUESTS
                && retryAfter.filter(SafeAuthenticationFailure::isPositive).isEmpty()) {
            throw new IllegalArgumentException(
                    "too_many_requests requires a positive retryAfter");
        }
    }

    public static SafeAuthenticationFailure invalidCredentials() {
        return INVALID_CREDENTIALS;
    }

    public static SafeAuthenticationFailure tooManyRequests(Duration retryAfter) {
        return new SafeAuthenticationFailure(
                AuthenticationFailureCode.TOO_MANY_REQUESTS,
                "Too many requests",
                Optional.of(Objects.requireNonNull(retryAfter, "retryAfter")));
    }

    private static boolean isPositive(Duration value) {
        return !value.isZero() && !value.isNegative();
    }
}
