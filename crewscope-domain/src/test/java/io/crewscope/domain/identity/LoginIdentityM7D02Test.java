package io.crewscope.domain.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** M7-D02 provider identity, immutable subject and lifecycle contract. */
class LoginIdentityM7D02Test {

    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.from(Instant.parse("2026-08-28T09:00:00Z"));
    private static final UtcTimestamp AUTHENTICATED_AT =
            UtcTimestamp.from(Instant.parse("2026-08-28T09:01:00Z"));

    @Test
    void providerKeyNormalizesConfiguredCompatibilityAndCaseAliases() {
        IdentityProviderKey provider = new IdentityProviderKey("  ＯＩＤＣ/Corporate-SSO  ");

        assertEquals("oidc/corporate-sso", provider.value());
        assertFalse(provider.isLocal());
        assertEquals(IdentityProviderKey.local(), new IdentityProviderKey("LOCAL"));
    }

    @Test
    void providerKeyRejectsNonCanonicalPathsAndLengthOverflow() {
        assertEquals(
                100,
                new IdentityProviderKey(
                                "a".repeat(IdentityProviderKey.MAX_SEGMENT_LENGTH)
                                        + "/"
                                        + "b".repeat(35))
                        .value()
                        .length());
        assertThrows(DomainValidationException.class, () -> new IdentityProviderKey("/oidc"));
        assertThrows(DomainValidationException.class, () -> new IdentityProviderKey("oidc/"));
        assertThrows(DomainValidationException.class, () -> new IdentityProviderKey("oidc//corp"));
        assertThrows(DomainValidationException.class, () -> new IdentityProviderKey("oidc corporate"));
        assertThrows(DomainValidationException.class, () -> new IdentityProviderKey("oidc\u202E/corp"));
        assertThrows(
                DomainValidationException.class,
                () -> new IdentityProviderKey(
                        "a".repeat(IdentityProviderKey.MAX_SEGMENT_LENGTH + 1)));
        assertThrows(
                DomainValidationException.class,
                () -> new IdentityProviderKey("a".repeat(IdentityProviderKey.MAX_LENGTH + 1)));
    }

    @Test
    void subjectPreservesExactProviderTextAndAlwaysRedactsStringOutput() {
        LoginIdentitySubject decomposed = new LoginIdentitySubject("subject-E\u0301");
        LoginIdentitySubject composed = new LoginIdentitySubject("subject-É");

        assertEquals("subject-E\u0301", decomposed.value());
        assertNotEquals(decomposed, composed);
        assertEquals("[REDACTED]", decomposed.toString());
    }

    @Test
    void subjectRejectsWhitespaceUnsafeUnicodeAndBothBudgets() {
        assertThrows(DomainValidationException.class, () -> new LoginIdentitySubject(" subject"));
        assertThrows(DomainValidationException.class, () -> new LoginIdentitySubject("subject "));
        assertThrows(DomainValidationException.class, () -> new LoginIdentitySubject("sub\nject"));
        assertThrows(DomainValidationException.class, () -> new LoginIdentitySubject("sub\u202Eject"));
        assertThrows(
                DomainValidationException.class,
                () -> new LoginIdentitySubject("s".repeat(LoginIdentitySubject.MAX_CODE_POINTS + 1)));
        assertThrows(
                DomainValidationException.class,
                () -> new LoginIdentitySubject("用".repeat(400)));
    }

    @Test
    void localIdentityDerivesItsStableSubjectOnlyFromAccountId() {
        UserAccountId accountId = UserAccountId.generate();
        LoginIdentity identity = LoginIdentity.local(
                LoginIdentityId.generate(), accountId, CREATED_AT);

        assertEquals(accountId, identity.accountId());
        assertEquals(IdentityProviderKey.local(), identity.provider());
        assertEquals(accountId.toString(), identity.subject().value());
        assertEquals(LoginIdentityStatus.ACTIVE, identity.status());
        assertTrue(identity.isUsable());
        assertEquals(0, identity.version());
    }

    @Test
    void localIdentityCannotUseMutableEmailOrAnotherAccountSubject() {
        UserAccountId accountId = UserAccountId.generate();

        assertThrows(
                DomainValidationException.class,
                () -> LoginIdentity.reconstitute(
                        LoginIdentityId.generate(),
                        accountId,
                        IdentityProviderKey.local(),
                        new LoginIdentitySubject("user@example.com"),
                        LoginIdentityStatus.ACTIVE,
                        Optional.empty(),
                        0,
                        LifecycleMetadata.createdAt(CREATED_AT)));
        assertThrows(
                DomainValidationException.class,
                () -> LoginIdentity.reconstitute(
                        LoginIdentityId.generate(),
                        accountId,
                        IdentityProviderKey.local(),
                        LoginIdentitySubject.local(UserAccountId.generate()),
                        LoginIdentityStatus.ACTIVE,
                        Optional.empty(),
                        0,
                        LifecycleMetadata.createdAt(CREATED_AT)));
    }

    @Test
    void externalFactoryRejectsLocalAndAcceptsOpaqueFutureProviderSubject() {
        UserAccountId accountId = UserAccountId.generate();
        LoginIdentity identity = LoginIdentity.external(
                LoginIdentityId.generate(),
                accountId,
                new IdentityProviderKey("oidc/corporate"),
                new LoginIdentitySubject("Subject-42"),
                CREATED_AT);

        assertEquals("Subject-42", identity.subject().value());
        assertThrows(
                DomainValidationException.class,
                () -> LoginIdentity.external(
                        LoginIdentityId.generate(),
                        accountId,
                        IdentityProviderKey.local(),
                        LoginIdentitySubject.local(accountId),
                        CREATED_AT));
    }

    @Test
    void stateMachineDisablesReactivatesAndTerminallyRevokesWithoutChangingSubject() {
        LoginIdentity original = externalIdentity();
        LoginIdentity disabled = original.transitionTo(LoginIdentityStatus.DISABLED, AUTHENTICATED_AT);
        LoginIdentity active = disabled.transitionTo(LoginIdentityStatus.ACTIVE, AUTHENTICATED_AT);
        LoginIdentity revoked = active.transitionTo(LoginIdentityStatus.REVOKED, AUTHENTICATED_AT);

        assertFalse(disabled.isUsable());
        assertTrue(active.isUsable());
        assertFalse(revoked.isUsable());
        assertEquals(original.subject(), revoked.subject());
        assertEquals(original.provider(), revoked.provider());
        assertEquals(3, revoked.version());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> revoked.transitionTo(LoginIdentityStatus.ACTIVE, AUTHENTICATED_AT));
    }

    @Test
    void successfulAuthenticationAdvancesOnlyObservationAndVersion() {
        LoginIdentity original = externalIdentity();
        LoginIdentity authenticated = original.recordAuthentication(AUTHENTICATED_AT);

        assertEquals(Optional.of(AUTHENTICATED_AT), authenticated.lastAuthenticatedAt());
        assertEquals(1, authenticated.version());
        assertEquals(original.subject(), authenticated.subject());
        assertEquals(AUTHENTICATED_AT, authenticated.lifecycle().updatedAt());
    }

    @Test
    void authenticationRejectsInactiveAndBackwardObservationTime() {
        LoginIdentity authenticated = externalIdentity().recordAuthentication(AUTHENTICATED_AT);
        LoginIdentity disabled = authenticated.transitionTo(
                LoginIdentityStatus.DISABLED, AUTHENTICATED_AT);

        assertThrows(
                DomainValidationException.class,
                () -> authenticated.recordAuthentication(CREATED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> disabled.recordAuthentication(AUTHENTICATED_AT));
    }

    @Test
    void compositeKeysCloseGlobalAndPerAccountUniquenessCoordinates() {
        LoginIdentity identity = externalIdentity();

        assertEquals(identity.provider(), identity.identityKey().provider());
        assertEquals(identity.subject(), identity.identityKey().subject());
        assertEquals(identity.accountId(), identity.accountProviderKey().accountId());
        assertEquals(identity.provider(), identity.accountProviderKey().provider());
        assertFalse(identity.identityKey().toString().contains(identity.subject().value()));
        assertTrue(identity.identityKey().toString().contains("REDACTED"));
    }

    @Test
    void reconstitutionRejectsAuthenticationOutsideLifecycleAndVersionOverflow() {
        LoginIdentity identity = externalIdentity();

        assertThrows(
                DomainValidationException.class,
                () -> LoginIdentity.reconstitute(
                        identity.id(),
                        identity.accountId(),
                        identity.provider(),
                        identity.subject(),
                        identity.status(),
                        Optional.of(AUTHENTICATED_AT),
                        0,
                        LifecycleMetadata.createdAt(CREATED_AT)));

        LoginIdentity maximum = LoginIdentity.reconstitute(
                identity.id(),
                identity.accountId(),
                identity.provider(),
                identity.subject(),
                identity.status(),
                Optional.empty(),
                Long.MAX_VALUE,
                LifecycleMetadata.createdAt(CREATED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> maximum.transitionTo(LoginIdentityStatus.DISABLED, AUTHENTICATED_AT));
    }

    private static LoginIdentity externalIdentity() {
        return LoginIdentity.external(
                LoginIdentityId.generate(),
                UserAccountId.generate(),
                new IdentityProviderKey("oidc/corporate"),
                new LoginIdentitySubject("Subject-42"),
                CREATED_AT);
    }
}
