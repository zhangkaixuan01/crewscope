package io.crewscope.domain.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** M7-D03 registration, password and temporary-login-lock domain contract. */
class AuthenticationPolicyM7D03Test {

    private static final UtcTimestamp STARTED_AT =
            UtcTimestamp.from(Instant.parse("2026-08-28T10:00:00Z"));
    private static final LoginAttemptPolicy LOGIN_POLICY = LoginAttemptPolicy.standard();
    private static final PasswordPolicy PASSWORD_POLICY = PasswordPolicy.standard();

    @Test
    void registrationModesSeparateOpenInvitationAndDisabledPaths() {
        assertTrue(RegistrationMode.OPEN.allowsRegistration(false));
        assertTrue(RegistrationMode.OPEN.allowsRegistration(true));
        assertTrue(RegistrationMode.OPEN.acceptsOpenRegistration());

        assertFalse(RegistrationMode.INVITE_ONLY.allowsRegistration(false));
        assertTrue(RegistrationMode.INVITE_ONLY.allowsRegistration(true));
        assertTrue(RegistrationMode.INVITE_ONLY.requiresInvitation());

        assertFalse(RegistrationMode.DISABLED.allowsRegistration(false));
        assertFalse(RegistrationMode.DISABLED.allowsRegistration(true));
    }

    @Test
    void registrationPasswordUsesFrozenCodePointAndUtf8Budgets() {
        assertEquals(
                PasswordPolicyResult.TOO_SHORT,
                PASSWORD_POLICY.evaluateForRegistration("a".repeat(11)));
        assertEquals(
                PasswordPolicyResult.ACCEPTED,
                PASSWORD_POLICY.evaluateForRegistration("a".repeat(12)));
        assertEquals(
                PasswordPolicyResult.ACCEPTED,
                PASSWORD_POLICY.evaluateForRegistration("a".repeat(128)));
        assertEquals(
                PasswordPolicyResult.TOO_LONG,
                PASSWORD_POLICY.evaluateForRegistration("a".repeat(129)));
        assertEquals(
                PasswordPolicyResult.ACCEPTED,
                PASSWORD_POLICY.evaluateForRegistration("😀".repeat(128)));
        assertEquals(
                PasswordPolicyResult.TOO_LARGE,
                PASSWORD_POLICY.evaluateForRegistration("😀".repeat(129)));
    }

    @Test
    void registrationRejectsStableLocalCommonPasswordSetWithoutRetainingInput() {
        assertEquals(
                PasswordPolicyResult.COMMON_PASSWORD,
                PASSWORD_POLICY.evaluateForRegistration("password1234"));
        assertEquals(
                PasswordPolicyResult.COMMON_PASSWORD,
                PASSWORD_POLICY.evaluateForRegistration("PASSWORD1234"));
        assertEquals(
                PasswordPolicyResult.ACCEPTED,
                PASSWORD_POLICY.evaluateForRegistration("unique passphrase 2026"));
        assertFalse(PasswordPolicyResult.COMMON_PASSWORD.toString().contains("password1234"));
    }

    @Test
    void passwordPolicyDoesNotTrimOrNormalizeAcceptedHashInput() {
        assertEquals(
                PasswordPolicyResult.ACCEPTED,
                PASSWORD_POLICY.evaluateForRegistration("  strong password  "));
        assertEquals(
                PasswordPolicyResult.ACCEPTED,
                PASSWORD_POLICY.evaluateForRegistration("Strong-E\u0301-Password"));
        assertEquals(
                PasswordPolicyResult.ACCEPTED,
                PASSWORD_POLICY.evaluateForRegistration("Strong-É-Password"));
    }

    @Test
    void authenticationBudgetAllowsLegacyShortInputButRejectsUnsafeMaximumsFirst() {
        assertEquals(
                PasswordPolicyResult.ACCEPTED,
                PASSWORD_POLICY.evaluateForAuthentication(""));
        assertEquals(
                PasswordPolicyResult.ACCEPTED,
                PASSWORD_POLICY.evaluateForAuthentication("short"));
        assertEquals(
                PasswordPolicyResult.TOO_LONG,
                PASSWORD_POLICY.evaluateForAuthentication("a".repeat(129)));
        assertEquals(
                PasswordPolicyResult.TOO_LARGE,
                PASSWORD_POLICY.evaluateForAuthentication("😀".repeat(129)));
        assertEquals(
                PasswordPolicyResult.INVALID_ENCODING,
                PASSWORD_POLICY.evaluateForAuthentication(null));
        assertEquals(
                PasswordPolicyResult.INVALID_ENCODING,
                PASSWORD_POLICY.evaluateForAuthentication("broken-\uD800"));
    }

    @Test
    void standardLoginPolicyFreezesSpikeLimitsAndBoundarySemantics() {
        assertEquals(10, LOGIN_POLICY.identifierAttemptLimit());
        assertEquals(Duration.ofMinutes(15), LOGIN_POLICY.identifierWindow());
        assertEquals(60, LOGIN_POLICY.controlledNetworkAttemptLimit());
        assertEquals(Duration.ofMinutes(5), LOGIN_POLICY.controlledNetworkWindow());
        assertEquals(10, LOGIN_POLICY.accountFailureLimit());
        assertEquals(Duration.ofMinutes(15), LOGIN_POLICY.accountFailureWindow());
        assertEquals(Duration.ofMinutes(15), LOGIN_POLICY.temporaryLockDuration());
        assertEquals(Duration.ofMillis(100), LOGIN_POLICY.hashAdmissionWait());
        assertEquals(Duration.ofSeconds(1), LOGIN_POLICY.retryAfter());

        assertTrue(LOGIN_POLICY.allowsIdentifierAttempt(9));
        assertFalse(LOGIN_POLICY.allowsIdentifierAttempt(10));
        assertTrue(LOGIN_POLICY.allowsControlledNetworkAttempt(59));
        assertFalse(LOGIN_POLICY.allowsControlledNetworkAttempt(60));
        assertFalse(LOGIN_POLICY.shouldTemporarilyLock(9));
        assertTrue(LOGIN_POLICY.shouldTemporarilyLock(10));
    }

    @Test
    void loginPolicyRejectsInvalidConfigurationAndCounters() {
        assertThrows(DomainValidationException.class, () -> LOGIN_POLICY.allowsIdentifierAttempt(-1));
        assertThrows(
                DomainValidationException.class,
                () -> new LoginAttemptPolicy(
                        10,
                        Duration.ZERO,
                        60,
                        Duration.ofMinutes(5),
                        10,
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(15),
                        Duration.ofMillis(100),
                        Duration.ofSeconds(1)));
        assertThrows(
                DomainValidationException.class,
                () -> new LoginAttemptPolicy(
                        9,
                        Duration.ofMinutes(15),
                        60,
                        Duration.ofMinutes(5),
                        10,
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(15),
                        Duration.ofMillis(100),
                        Duration.ofSeconds(1)));
    }

    @Test
    void tenthKnownAccountFailureCreatesFifteenMinuteTemporaryLock() {
        AccountLoginAttemptState state = AccountLoginAttemptState.clear(STARTED_AT);
        for (int attempt = 1; attempt <= 9; attempt++) {
            state = state.recordFailure(plusSeconds(attempt), LOGIN_POLICY);
            assertFalse(state.isTemporarilyLocked(plusSeconds(attempt)));
        }

        UtcTimestamp tenthFailure = plusSeconds(10);
        state = state.recordFailure(tenthFailure, LOGIN_POLICY);

        assertEquals(10, state.failureCount());
        assertTrue(state.isTemporarilyLocked(tenthFailure));
        assertEquals(
                Optional.of(UtcTimestamp.from(
                        tenthFailure.value().plus(LOGIN_POLICY.temporaryLockDuration()))),
                state.lockedUntil());
        assertFalse(state.toString().contains(tenthFailure.toString()));
    }

    @Test
    void activeTemporaryLockConsumesNoAdditionalFailureAndRejectsSuccessFact() {
        AccountLoginAttemptState locked = lockedState();
        UtcTimestamp duringLock = plusMinutes(5);

        AccountLoginAttemptState observed = locked.recordFailure(duringLock, LOGIN_POLICY);

        assertEquals(LOGIN_POLICY.accountFailureLimit(), observed.failureCount());
        assertTrue(observed.isTemporarilyLocked(duringLock));
        assertThrows(
                DomainValidationException.class,
                () -> observed.recordSuccess(duringLock, LOGIN_POLICY));
    }

    @Test
    void temporaryLockExpiresAtExactBoundaryAndAllowsSuccessfulReset() {
        AccountLoginAttemptState locked = lockedState();
        UtcTimestamp expiresAt = locked.lockedUntil().orElseThrow();

        AccountLoginAttemptState expired = locked.observe(expiresAt, LOGIN_POLICY);
        AccountLoginAttemptState succeeded = expired.recordSuccess(expiresAt, LOGIN_POLICY);

        assertFalse(expired.isTemporarilyLocked(expiresAt));
        assertTrue(expired.failures().isEmpty());
        assertEquals(0, succeeded.failureCount());
        assertTrue(succeeded.lockedUntil().isEmpty());
    }

    @Test
    void successfulAuthenticationClearsAccountFailuresBeforeThreshold() {
        AccountLoginAttemptState state = AccountLoginAttemptState.clear(STARTED_AT)
                .recordFailure(plusSeconds(1), LOGIN_POLICY)
                .recordFailure(plusSeconds(2), LOGIN_POLICY);

        AccountLoginAttemptState succeeded =
                state.recordSuccess(plusSeconds(3), LOGIN_POLICY);

        assertEquals(0, succeeded.failureCount());
        assertTrue(succeeded.lockedUntil().isEmpty());
    }

    @Test
    void slidingFailureWindowExcludesTheExactFifteenMinuteFloor() {
        AccountLoginAttemptState state = AccountLoginAttemptState.clear(STARTED_AT);
        for (int attempt = 0; attempt < 9; attempt++) {
            state = state.recordFailure(plusSeconds(attempt), LOGIN_POLICY);
        }
        UtcTimestamp exactFloorExpiry = plusMinutes(15);

        AccountLoginAttemptState result =
                state.recordFailure(exactFloorExpiry, LOGIN_POLICY);

        assertEquals(9, result.failureCount());
        assertFalse(result.isTemporarilyLocked(exactFloorExpiry));
    }

    @Test
    void attemptStateRejectsBackwardTimeAndImpossibleStoreShapes() {
        AccountLoginAttemptState state = AccountLoginAttemptState.clear(plusSeconds(10));
        assertThrows(
                DomainValidationException.class,
                () -> state.recordFailure(STARTED_AT, LOGIN_POLICY));

        List<UtcTimestamp> unordered = new ArrayList<>();
        unordered.add(plusSeconds(2));
        unordered.add(plusSeconds(1));
        assertThrows(
                DomainValidationException.class,
                () -> AccountLoginAttemptState.reconstitute(
                        unordered, Optional.empty(), plusSeconds(3), LOGIN_POLICY));

        List<UtcTimestamp> threshold = new ArrayList<>();
        for (int attempt = 0; attempt < LOGIN_POLICY.accountFailureLimit(); attempt++) {
            threshold.add(plusSeconds(attempt));
        }
        Collections.reverse(threshold);
        assertThrows(
                DomainValidationException.class,
                () -> AccountLoginAttemptState.reconstitute(
                        threshold,
                        Optional.of(plusMinutes(15)),
                        plusSeconds(10),
                        LOGIN_POLICY));
        Collections.reverse(threshold);
        assertThrows(
                DomainValidationException.class,
                () -> AccountLoginAttemptState.reconstitute(
                        threshold, Optional.empty(), plusSeconds(10), LOGIN_POLICY));
    }

    private static AccountLoginAttemptState lockedState() {
        AccountLoginAttemptState state = AccountLoginAttemptState.clear(STARTED_AT);
        for (int attempt = 1; attempt <= LOGIN_POLICY.accountFailureLimit(); attempt++) {
            state = state.recordFailure(plusSeconds(attempt), LOGIN_POLICY);
        }
        return state;
    }

    private static UtcTimestamp plusSeconds(long seconds) {
        return UtcTimestamp.from(STARTED_AT.value().plusSeconds(seconds));
    }

    private static UtcTimestamp plusMinutes(long minutes) {
        return UtcTimestamp.from(STARTED_AT.value().plus(Duration.ofMinutes(minutes)));
    }
}
