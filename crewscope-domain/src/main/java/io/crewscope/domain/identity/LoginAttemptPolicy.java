package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.time.Duration;
import java.util.Objects;

/** Fixed login-rate, account-failure and hash-admission policy from ADR-025. */
public record LoginAttemptPolicy(
        int identifierAttemptLimit,
        Duration identifierWindow,
        int controlledNetworkAttemptLimit,
        Duration controlledNetworkWindow,
        int accountFailureLimit,
        Duration accountFailureWindow,
        Duration temporaryLockDuration,
        Duration hashAdmissionWait,
        Duration retryAfter) {

    private static final LoginAttemptPolicy STANDARD = new LoginAttemptPolicy(
            10,
            Duration.ofMinutes(15),
            60,
            Duration.ofMinutes(5),
            10,
            Duration.ofMinutes(15),
            Duration.ofMinutes(15),
            Duration.ofMillis(100),
            Duration.ofSeconds(1));

    public LoginAttemptPolicy {
        requirePositive(identifierAttemptLimit, "identifierAttemptLimit");
        identifierWindow = requirePositive(identifierWindow, "identifierWindow");
        requirePositive(controlledNetworkAttemptLimit, "controlledNetworkAttemptLimit");
        controlledNetworkWindow =
                requirePositive(controlledNetworkWindow, "controlledNetworkWindow");
        requirePositive(accountFailureLimit, "accountFailureLimit");
        accountFailureWindow = requirePositive(accountFailureWindow, "accountFailureWindow");
        temporaryLockDuration = requirePositive(temporaryLockDuration, "temporaryLockDuration");
        hashAdmissionWait = requirePositive(hashAdmissionWait, "hashAdmissionWait");
        retryAfter = requirePositive(retryAfter, "retryAfter");
        if (accountFailureLimit > identifierAttemptLimit) {
            throw invalid("accountFailureLimit", "must not exceed the identifier attempt limit");
        }
    }

    public static LoginAttemptPolicy standard() {
        return STANDARD;
    }

    /** Counts attempts already admitted in the active identifier window. */
    public boolean allowsIdentifierAttempt(int attemptsAlreadyInWindow) {
        return requireNonNegative(attemptsAlreadyInWindow, "identifierAttempts")
                < identifierAttemptLimit;
    }

    /** Counts attempts already admitted in the active controlled-network window. */
    public boolean allowsControlledNetworkAttempt(int attemptsAlreadyInWindow) {
        return requireNonNegative(attemptsAlreadyInWindow, "controlledNetworkAttempts")
                < controlledNetworkAttemptLimit;
    }

    /** Counts known-account failures including the current failed authentication. */
    public boolean shouldTemporarilyLock(int failuresIncludingCurrent) {
        return requireNonNegative(failuresIncludingCurrent, "accountFailures")
                >= accountFailureLimit;
    }

    private static int requireNonNegative(int value, String field) {
        if (value < 0) {
            throw invalid(field, "must not be negative");
        }
        return value;
    }

    private static void requirePositive(int value, String field) {
        if (value < 1) {
            throw invalid(field, "must be positive");
        }
    }

    private static Duration requirePositive(Duration value, String field) {
        Duration required = Objects.requireNonNull(value, field);
        if (required.isZero() || required.isNegative()) {
            throw invalid(field, "must be positive");
        }
        return required;
    }

    private static DomainValidationException invalid(String field, String reason) {
        return new DomainValidationException("loginAttemptPolicy." + field, reason);
    }
}
