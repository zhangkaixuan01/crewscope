package io.crewscope.domain.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.net.IDN;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** M7-D01 account normalization, lifecycle, authority and non-enumeration contract. */
class UserAccountM7D01Test {

    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.from(Instant.parse("2026-08-28T08:00:00Z"));
    private static final UtcTimestamp CHANGED_AT =
            UtcTimestamp.from(Instant.parse("2026-08-28T08:01:00Z"));

    @Test
    void usernameKeepsDisplayValueSeparateFromCompatibilityNormalizedKey() {
        Username username = new Username("  ＫＡＩ_用户  ");

        assertEquals("ＫＡＩ_用户", username.displayValue());
        assertEquals("kai_用户", username.normalizedValue());
        assertEquals(username, new Username("ＫＡＩ_用户"));
        assertNotEquals(username, new Username("kai_用户"));
    }

    @Test
    void usernameUsesCodePointLengthsAndCanonicalUnicodeDisplay() {
        String maximum = "用".repeat(Username.MAX_LENGTH);
        Username decomposed = new Username("E\u0301li");

        assertEquals(Username.MAX_LENGTH, new Username(maximum).displayValue().codePointCount(0, maximum.length()));
        assertEquals("Éli", decomposed.displayValue());
        assertEquals("éli", decomposed.normalizedValue());
        assertThrows(DomainValidationException.class, () -> new Username("用".repeat(2)));
        assertThrows(
                DomainValidationException.class,
                () -> new Username("用".repeat(Username.MAX_LENGTH + 1)));
        assertThrows(
                DomainValidationException.class,
                () -> new Username("a".repeat(63) + "ﬃ"));
    }

    @Test
    void usernameRejectsUnsafeOrAmbiguousSeparatorShapes() {
        assertThrows(DomainValidationException.class, () -> new Username("kai..lin"));
        assertThrows(DomainValidationException.class, () -> new Username("-kailin"));
        assertThrows(DomainValidationException.class, () -> new Username("kailin_"));
        assertThrows(DomainValidationException.class, () -> new Username("kai lin"));
        assertThrows(DomainValidationException.class, () -> new Username("kai_\u0301lin"));
        assertThrows(DomainValidationException.class, () -> new Username("kai\u202Elin"));
        assertThrows(DomainValidationException.class, () -> new Username("kai🚀"));
    }

    @Test
    void emailNormalizationClosesCaseFullWidthAndInternationalDomainAliases() {
        String display = "  Ｋai+测试＠例子.公司  ";
        NormalizedEmail email = NormalizedEmail.fromDisplayValue(display);

        assertEquals(
                "kai+测试@" + IDN.toASCII("例子.公司"),
                email.value());
        assertEquals(email, new NormalizedEmail("KAI+测试@例子.公司"));
        assertEquals("[REDACTED]", email.toString());
    }

    @Test
    void emailRejectsInvalidLocalDomainAndUnicodeBoundaries() {
        assertThrows(DomainValidationException.class, () -> new NormalizedEmail("a..b@example.com"));
        assertThrows(DomainValidationException.class, () -> new NormalizedEmail(".ab@example.com"));
        assertThrows(DomainValidationException.class, () -> new NormalizedEmail("ab@@example.com"));
        assertThrows(DomainValidationException.class, () -> new NormalizedEmail("ab@-example.com"));
        assertThrows(DomainValidationException.class, () -> new NormalizedEmail("ab@example.com."));
        assertThrows(DomainValidationException.class, () -> new NormalizedEmail("ab @example.com"));
        assertThrows(DomainValidationException.class, () -> new NormalizedEmail("ab\u202E@example.com"));
        assertThrows(DomainValidationException.class, () -> new NormalizedEmail("ab🚀@example.com"));
        assertThrows(
                DomainValidationException.class,
                () -> new NormalizedEmail("a".repeat(65) + "@example.com"));
    }

    @Test
    void registrationCreatesActiveUserWithIndependentBusinessAndSecurityVersions() {
        UserAccount account = user();

        assertEquals(AccountStatus.ACTIVE, account.status());
        assertEquals(PlatformRole.USER, account.platformRole());
        assertEquals(0, account.version());
        assertEquals(SecurityVersion.initial(), account.securityVersion());
        assertEquals(CREATED_AT, account.lifecycle().createdAt());
        assertTrue(account.canAuthenticate());
        assertFalse(account.allowsPlatformOperations());
    }

    @Test
    void trustedBootstrapPathIsTheOnlyFactoryThatCreatesAnOperator() {
        UserAccount operator = UserAccount.bootstrapOperator(
                UserAccountId.generate(),
                "operator",
                "operator@example.com",
                "CrewScope Operator",
                CREATED_AT);

        assertEquals(PlatformRole.OPERATOR, operator.platformRole());
        assertTrue(operator.allowsPlatformOperations());
        assertFalse(PlatformRole.USER.allowsPlatformOperations());
        assertTrue(PlatformRole.OPERATOR.allowsPlatformOperations());
    }

    @Test
    void profileChangesAdvanceBusinessVersionAndKeepBothIdentifierRepresentationsAtomic() {
        UserAccount changed = user()
                .changeUsername("Ｎew_用户", CHANGED_AT)
                .changeEmail("Ｎew＠例子.公司", CHANGED_AT)
                .changeDisplayName("  New Display  ", CHANGED_AT);

        assertEquals(3, changed.version());
        assertEquals("Ｎew_用户", changed.username().displayValue());
        assertEquals("new_用户", changed.username().normalizedValue());
        assertEquals("Ｎew＠例子.公司", changed.email());
        assertEquals("new@" + IDN.toASCII("例子.公司"), changed.normalizedEmail().value());
        assertEquals("New Display", changed.displayName());
        assertEquals(SecurityVersion.initial(), changed.securityVersion());
        assertEquals(CHANGED_AT, changed.lifecycle().updatedAt());
    }

    @Test
    void accountStateMachineAllowsOnlyTheClosedTransitionGraph() {
        UserAccount locked = user().transitionTo(AccountStatus.LOCKED, CHANGED_AT);
        UserAccount unlocked = locked.transitionTo(AccountStatus.ACTIVE, CHANGED_AT);
        UserAccount disabled = unlocked.transitionTo(AccountStatus.DISABLED, CHANGED_AT);
        UserAccount enabled = disabled.transitionTo(AccountStatus.ACTIVE, CHANGED_AT);
        UserAccount archived = enabled.transitionTo(AccountStatus.ARCHIVED, CHANGED_AT);

        assertFalse(locked.canAuthenticate());
        assertTrue(unlocked.canAuthenticate());
        assertFalse(disabled.canAuthenticate());
        assertTrue(enabled.canAuthenticate());
        assertFalse(archived.canAuthenticate());
        assertEquals(new SecurityVersion(6), archived.securityVersion());
        assertEquals(5, archived.version());

        assertEquals(
                AccountStatus.DISABLED,
                user().transitionTo(AccountStatus.LOCKED, CHANGED_AT)
                        .transitionTo(AccountStatus.DISABLED, CHANGED_AT)
                        .status());
        assertEquals(
                AccountStatus.ARCHIVED,
                user().transitionTo(AccountStatus.DISABLED, CHANGED_AT)
                        .transitionTo(AccountStatus.ARCHIVED, CHANGED_AT)
                        .status());
    }

    @Test
    void stateMachineRejectsNoOpReverseAndArchivedMutations() {
        UserAccount disabled = user().transitionTo(AccountStatus.DISABLED, CHANGED_AT);
        UserAccount archived = disabled.transitionTo(AccountStatus.ARCHIVED, CHANGED_AT);

        assertThrows(
                InvalidStateTransitionException.class,
                () -> user().transitionTo(AccountStatus.ACTIVE, CHANGED_AT));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> disabled.transitionTo(AccountStatus.LOCKED, CHANGED_AT));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.transitionTo(AccountStatus.ACTIVE, CHANGED_AT));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.changeEmail("other@example.com", CHANGED_AT));
    }

    @Test
    void securityVersionExplicitlyRevokesSessionsAndCannotOverflow() {
        UserAccount advanced = user().advanceSecurityVersion(CHANGED_AT);

        assertEquals(new SecurityVersion(2), advanced.securityVersion());
        assertEquals(1, advanced.version());
        assertThrows(DomainValidationException.class, () -> new SecurityVersion(0));
        assertThrows(
                DomainValidationException.class,
                () -> new SecurityVersion(Long.MAX_VALUE).next());
    }

    @Test
    void reconstitutionRejectsMismatchedEmailProjectionAndInvalidVersion() {
        UserAccount account = user();

        assertThrows(
                DomainValidationException.class,
                () -> UserAccount.reconstitute(
                        account.id(),
                        account.username(),
                        account.email(),
                        new NormalizedEmail("other@example.com"),
                        account.displayName(),
                        account.status(),
                        account.platformRole(),
                        account.securityVersion(),
                        account.version(),
                        account.lifecycle()));
        assertThrows(
                DomainValidationException.class,
                () -> UserAccount.reconstitute(
                        account.id(),
                        account.username(),
                        account.email(),
                        account.normalizedEmail(),
                        account.displayName(),
                        account.status(),
                        account.platformRole(),
                        account.securityVersion(),
                        -1,
                        account.lifecycle()));
    }

    @Test
    void usernameAndEmailUniqueFailuresHaveOneNonEnumeratingPublicShape() {
        AccountIdentifierConflictException usernameConflict =
                new AccountIdentifierConflictException();
        AccountIdentifierConflictException emailConflict =
                new AccountIdentifierConflictException();

        assertEquals(DomainErrorCode.ACCOUNT_IDENTIFIER_CONFLICT, usernameConflict.error().code());
        assertEquals(usernameConflict.error(), emailConflict.error());
        assertTrue(usernameConflict.error().details().isEmpty());
        assertFalse(usernameConflict.getMessage().toLowerCase().contains("username"));
        assertFalse(usernameConflict.getMessage().toLowerCase().contains("email"));
    }

    @Test
    void displayAndLifecycleUnicodeValuesRejectUnsafeContentAndTimeReversal() {
        assertThrows(
                DomainValidationException.class,
                () -> UserAccount.register(
                        UserAccountId.generate(),
                        "valid-user",
                        "valid@example.com",
                        "unsafe\u202Ename",
                        CREATED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> user().changeDisplayName(
                        "changed",
                        UtcTimestamp.from(Instant.parse("2026-08-28T07:59:59Z"))));
    }

    @Test
    void reconstitutedOperatorRetainsStableIdentityAndVersions() {
        UserAccountId id = UserAccountId.generate();
        LifecycleMetadata lifecycle = LifecycleMetadata.createdAt(CREATED_AT).modifiedAt(CHANGED_AT);
        UserAccount account = UserAccount.reconstitute(
                id,
                new Username("operator"),
                "Operator@Example.com",
                new NormalizedEmail("operator@example.com"),
                "Operator",
                AccountStatus.DISABLED,
                PlatformRole.OPERATOR,
                new SecurityVersion(9),
                12,
                lifecycle);

        assertEquals(id, account.id());
        assertEquals(12, account.version());
        assertEquals(new SecurityVersion(9), account.securityVersion());
        assertFalse(account.allowsPlatformOperations());
        assertEquals(CHANGED_AT, account.lifecycle().updatedAt());
    }

    private static UserAccount user() {
        return UserAccount.register(
                UserAccountId.generate(),
                "Kai.User",
                "Kai.User@Example.com",
                "Kai",
                CREATED_AT);
    }
}
