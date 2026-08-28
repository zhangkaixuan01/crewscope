package io.crewscope.domain.identity.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.LocalPasswordHash;
import io.crewscope.domain.identity.LoginIdentitySubject;
import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.InvitationTokenDigest;
import io.crewscope.domain.team.event.TeamInvitationAccepted;
import io.crewscope.domain.team.event.TeamInvitationCreated;
import io.crewscope.domain.team.event.TeamInvitationMembershipResult;
import io.crewscope.domain.team.event.TeamInvitationRevoked;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** M7-D06 domain event shape, privilege and sensitive-value boundary tests. */
class AuthenticationSecurityEventsM7D06Test {

    @Test
    void accountRegistrationSourceClosesTheOperatorPrivilegeBoundary() {
        assertEquals(
                PlatformRole.USER,
                new UserAccountRegistered(AccountRegistrationSource.OPEN, PlatformRole.USER)
                        .platformRole());
        assertEquals(
                PlatformRole.USER,
                new UserAccountRegistered(AccountRegistrationSource.INVITATION, PlatformRole.USER)
                        .platformRole());
        assertEquals(
                PlatformRole.OPERATOR,
                new UserAccountRegistered(AccountRegistrationSource.BOOTSTRAP, PlatformRole.OPERATOR)
                        .platformRole());

        assertThrows(
                IllegalArgumentException.class,
                () -> new UserAccountRegistered(
                        AccountRegistrationSource.OPEN, PlatformRole.OPERATOR));
        assertThrows(
                IllegalArgumentException.class,
                () -> new UserAccountRegistered(
                        AccountRegistrationSource.BOOTSTRAP, PlatformRole.USER));
    }

    @Test
    void authenticationSuccessKeepsOnlyCanonicalProviderAndSafeFacts() {
        AuthenticationSucceeded event = new AuthenticationSucceeded(" LOCAL ", true, 3);

        assertEquals("local", event.provider());
        assertTrue(event.credentialUpgraded());
        assertEquals(3, event.securityVersion());
        assertThrows(
                RuntimeException.class,
                () -> new AuthenticationSucceeded("not a provider!", false, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthenticationSucceeded("local", false, 0));
    }

    @Test
    void authenticationFailureUsesOnlyFixedAggregateClassifications() {
        AuthenticationFailuresAggregated event = new AuthenticationFailuresAggregated(
                AuthenticationFailureClass.INVALID_CREDENTIALS, 10, 900);

        assertEquals(10, event.occurrenceCount());
        assertEquals(900, event.aggregationWindowSeconds());
        assertEquals(
                Set.of(
                        AuthenticationFailureClass.INVALID_CREDENTIALS,
                        AuthenticationFailureClass.IDENTIFIER_RATE_LIMITED,
                        AuthenticationFailureClass.NETWORK_RATE_LIMITED,
                        AuthenticationFailureClass.HASH_CAPACITY_EXHAUSTED,
                        AuthenticationFailureClass.AUTHENTICATION_STORE_UNAVAILABLE),
                Set.of(AuthenticationFailureClass.values()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthenticationFailuresAggregated(
                        AuthenticationFailureClass.INVALID_CREDENTIALS, 0, 900));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthenticationFailuresAggregated(
                        AuthenticationFailureClass.INVALID_CREDENTIALS, 1, 0));
    }

    @Test
    void temporaryLockAndLogoutExposePolicyShapeWithoutSessionIdentity() {
        AccountTemporarilyLocked locked = new AccountTemporarilyLocked(10, 900);
        AccountLoggedOut loggedOut =
                new AccountLoggedOut(AccountLogoutScope.ALL_SESSIONS, 4);

        assertEquals(10, locked.failureCount());
        assertEquals(900, locked.lockDurationSeconds());
        assertEquals(AccountLogoutScope.ALL_SESSIONS, loggedOut.scope());
        assertEquals(4, loggedOut.securityVersion());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccountTemporarilyLocked(0, 900));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccountLoggedOut(AccountLogoutScope.CURRENT_SESSION, 0));
    }

    @Test
    void profileAndPasswordEventsContainChangeEvidenceInsteadOfSubmittedValues() {
        AccountProfileChanged profile = new AccountProfileChanged(true, true, false);
        AccountPasswordChanged password = new AccountPasswordChanged(2, 5);

        assertTrue(profile.usernameChanged());
        assertTrue(profile.emailChanged());
        assertFalse(profile.displayNameChanged());
        assertEquals(2, password.credentialVersion());
        assertEquals(5, password.securityVersion());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccountProfileChanged(false, false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccountPasswordChanged(0, 1));
    }

    @Test
    void invitationEventsKeepStableResultsAndExcludeInvitationSecrets() {
        UUID accountId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        TeamInvitationCreated created = new TeamInvitationCreated(
                BuiltInTeamRole.MEMBER,
                true,
                UtcTimestamp.from(Instant.parse("2026-09-04T09:00:00Z")));
        TeamInvitationAccepted accepted = new TeamInvitationAccepted(
                accountId,
                memberId,
                BuiltInTeamRole.MEMBER,
                TeamInvitationMembershipResult.REUSED);
        TeamInvitationRevoked revoked =
                new TeamInvitationRevoked(BuiltInTeamRole.AUDITOR, false);

        assertTrue(created.targetRestricted());
        assertEquals(accountId, accepted.acceptedAccountId());
        assertEquals(memberId, accepted.acceptedMemberId());
        assertEquals(TeamInvitationMembershipResult.REUSED, accepted.membershipResult());
        assertFalse(revoked.targetRestricted());
        assertThrows(
                IllegalArgumentException.class,
                () -> new TeamInvitationCreated(
                        BuiltInTeamRole.TEAM_OWNER, false, created.expiresAt()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TeamInvitationAccepted(
                        new UUID(0, 0),
                        memberId,
                        BuiltInTeamRole.MEMBER,
                        TeamInvitationMembershipResult.CREATED));
    }

    @Test
    void payloadComponentsMatchTheReviewedM7Whitelist() {
        Map<Class<?>, Set<String>> expected = Map.of(
                UserAccountRegistered.class, Set.of("source", "platformRole"),
                AuthenticationSucceeded.class,
                        Set.of("provider", "credentialUpgraded", "securityVersion"),
                AuthenticationFailuresAggregated.class,
                        Set.of("failureClass", "occurrenceCount", "aggregationWindowSeconds"),
                AccountTemporarilyLocked.class,
                        Set.of("failureCount", "lockDurationSeconds"),
                AccountLoggedOut.class, Set.of("scope", "securityVersion"),
                AccountProfileChanged.class,
                        Set.of("usernameChanged", "emailChanged", "displayNameChanged"),
                AccountPasswordChanged.class,
                        Set.of("credentialVersion", "securityVersion"),
                TeamInvitationCreated.class,
                        Set.of("targetRole", "targetRestricted", "expiresAt"),
                TeamInvitationAccepted.class,
                        Set.of(
                                "acceptedAccountId",
                                "acceptedMemberId",
                                "targetRole",
                                "membershipResult"),
                TeamInvitationRevoked.class,
                        Set.of("targetRole", "targetRestricted"));

        expected.forEach((payloadType, fields) -> assertEquals(fields, componentNames(payloadType)));
    }

    @Test
    void payloadTypesCannotCarryCredentialTokenEmailOrRawErrorObjects() {
        Set<Class<?>> forbidden = Set.of(
                LocalPasswordHash.class,
                InvitationTokenDigest.class,
                NormalizedEmail.class,
                LoginIdentitySubject.class,
                byte[].class,
                char[].class,
                Throwable.class);
        for (Class<?> payloadType : payloadTypes()) {
            for (RecordComponent component : payloadType.getRecordComponents()) {
                assertFalse(
                        forbidden.stream().anyMatch(type ->
                                type.isAssignableFrom(component.getType())),
                        () -> payloadType.getSimpleName() + "." + component.getName());
            }
        }
    }

    private static Set<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<Class<?>> payloadTypes() {
        return Set.of(
                UserAccountRegistered.class,
                AuthenticationSucceeded.class,
                AuthenticationFailuresAggregated.class,
                AccountTemporarilyLocked.class,
                AccountLoggedOut.class,
                AccountProfileChanged.class,
                AccountPasswordChanged.class,
                TeamInvitationCreated.class,
                TeamInvitationAccepted.class,
                TeamInvitationRevoked.class);
    }
}
