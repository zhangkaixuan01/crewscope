package io.crewscope.application.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.AuthenticationFailureReason;
import io.crewscope.domain.identity.LoginAttemptPolicy;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** M7-D03 anonymous authentication failure collapse contract. */
class AuthenticationFailureM7D03Test {

    private static final LoginAttemptPolicy POLICY = LoginAttemptPolicy.standard();

    @Test
    void everyAccountAndCredentialFailureCollapsesToOneIdenticalPublicValue() {
        SafeAuthenticationFailure expected = SafeAuthenticationFailure.invalidCredentials();

        Arrays.stream(AuthenticationFailureReason.values())
                .filter(reason -> !reason.isCapacityFailure())
                .forEach(reason -> assertEquals(
                        expected, AuthenticationFailureMapper.toSafeFailure(reason, POLICY)));

        assertEquals(AuthenticationFailureCode.INVALID_CREDENTIALS, expected.code());
        assertEquals("invalid_credentials", expected.code().value());
        assertEquals("Invalid credentials", expected.message());
        assertTrue(expected.retryAfter().isEmpty());
    }

    @Test
    void rateAndHashCapacityFailuresCollapseToOneRetryablePublicValue() {
        SafeAuthenticationFailure expected =
                SafeAuthenticationFailure.tooManyRequests(Duration.ofSeconds(1));

        Arrays.stream(AuthenticationFailureReason.values())
                .filter(AuthenticationFailureReason::isCapacityFailure)
                .forEach(reason -> assertEquals(
                        expected, AuthenticationFailureMapper.toSafeFailure(reason, POLICY)));

        assertEquals(AuthenticationFailureCode.TOO_MANY_REQUESTS, expected.code());
        assertEquals("too_many_requests", expected.code().value());
        assertEquals(Optional.of(Duration.ofSeconds(1)), expected.retryAfter());
    }

    @Test
    void safeFailureShapeCannotRetainInternalReasonOrAccountFacts() {
        assertFalse(Arrays.stream(SafeAuthenticationFailure.class.getDeclaredFields())
                .map(Field::getName)
                .map(String::toLowerCase)
                .anyMatch(name -> name.contains("reason")
                        || name.contains("account")
                        || name.contains("identifier")
                        || name.contains("password")
                        || name.contains("subject")));
        assertFalse(SafeAuthenticationFailure.invalidCredentials()
                .toString()
                .contains(AuthenticationFailureReason.ACCOUNT_UNKNOWN.name()));
    }

    @Test
    void safeFailureRejectsInconsistentCodeAndRetryShapes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SafeAuthenticationFailure(
                        AuthenticationFailureCode.INVALID_CREDENTIALS,
                        "Invalid credentials",
                        Optional.of(Duration.ofSeconds(1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SafeAuthenticationFailure(
                        AuthenticationFailureCode.TOO_MANY_REQUESTS,
                        "Too many requests",
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> SafeAuthenticationFailure.tooManyRequests(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SafeAuthenticationFailure(
                        AuthenticationFailureCode.INVALID_CREDENTIALS,
                        "Account does not exist",
                        Optional.empty()));
    }
}
