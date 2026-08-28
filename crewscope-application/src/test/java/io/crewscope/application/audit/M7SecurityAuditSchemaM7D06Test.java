package io.crewscope.application.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.event.json.DomainEventEnvelopeJsonCodec;
import io.crewscope.application.team.InvitationMembershipDisposition;
import io.crewscope.domain.audit.AuditEventCategory;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.audit.AuditRetentionLevel;
import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.identity.event.AccountLoggedOut;
import io.crewscope.domain.identity.event.AccountLogoutScope;
import io.crewscope.domain.identity.event.AccountPasswordChanged;
import io.crewscope.domain.identity.event.AccountProfileChanged;
import io.crewscope.domain.identity.event.AccountRegistrationSource;
import io.crewscope.domain.identity.event.AccountTemporarilyLocked;
import io.crewscope.domain.identity.event.AuthenticationFailureClass;
import io.crewscope.domain.identity.event.AuthenticationFailuresAggregated;
import io.crewscope.domain.identity.event.AuthenticationSucceeded;
import io.crewscope.domain.identity.event.UserAccountRegistered;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.event.TeamInvitationAccepted;
import io.crewscope.domain.team.event.TeamInvitationCreated;
import io.crewscope.domain.team.event.TeamInvitationMembershipResult;
import io.crewscope.domain.team.event.TeamInvitationRevoked;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** M7-D06 exact event coordinates, Audit allowlists and serialized sensitive-field proof. */
class M7SecurityAuditSchemaM7D06Test {

    private static final Set<String> EVENT_TYPES = Set.of(
            "USER_ACCOUNT_REGISTERED",
            "AUTHENTICATION_SUCCEEDED",
            "AUTHENTICATION_FAILURES_AGGREGATED",
            "ACCOUNT_TEMPORARILY_LOCKED",
            "ACCOUNT_LOGGED_OUT",
            "ACCOUNT_PROFILE_CHANGED",
            "ACCOUNT_PASSWORD_CHANGED",
            "TEAM_INVITATION_CREATED",
            "TEAM_INVITATION_ACCEPTED",
            "TEAM_INVITATION_REVOKED");

    private final AuditEventTypeRegistry registry = CrewScopeAuditEventTypes.reviewedRegistry();
    private final DomainEventEnvelopeJsonCodec codec =
            new DomainEventEnvelopeJsonCodec(new ObjectMapper());

    @Test
    void registersExactlyTenM7V1SecurityCoordinates() {
        assertEquals(110, registry.size());
        assertDefinition(
                "USER_ACCOUNT_REGISTERED",
                AuditEventCategory.IDENTITY,
                AuditOutcome.SUCCEEDED);
        assertDefinition(
                "AUTHENTICATION_SUCCEEDED",
                AuditEventCategory.SECURITY,
                AuditOutcome.SUCCEEDED);
        assertDefinition(
                "AUTHENTICATION_FAILURES_AGGREGATED",
                AuditEventCategory.SECURITY,
                AuditOutcome.FAILED);
        assertDefinition(
                "ACCOUNT_TEMPORARILY_LOCKED",
                AuditEventCategory.SECURITY,
                AuditOutcome.DENIED);
        assertDefinition(
                "ACCOUNT_LOGGED_OUT",
                AuditEventCategory.SECURITY,
                AuditOutcome.SUCCEEDED);
        assertDefinition(
                "ACCOUNT_PROFILE_CHANGED",
                AuditEventCategory.IDENTITY,
                AuditOutcome.SUCCEEDED);
        assertDefinition(
                "ACCOUNT_PASSWORD_CHANGED",
                AuditEventCategory.SECURITY,
                AuditOutcome.SUCCEEDED);
        assertDefinition(
                "TEAM_INVITATION_CREATED", AuditEventCategory.TEAM, AuditOutcome.SUCCEEDED);
        assertDefinition(
                "TEAM_INVITATION_ACCEPTED", AuditEventCategory.TEAM, AuditOutcome.SUCCEEDED);
        assertDefinition(
                "TEAM_INVITATION_REVOKED", AuditEventCategory.TEAM, AuditOutcome.SUCCEEDED);

        for (String eventType : EVENT_TYPES) {
            assertTrue(registry.find(EventType.from(eventType), SchemaVersion.V2).isEmpty());
        }
    }

    @Test
    void auditSourceAllowlistsEqualTheEventRecordShapes() {
        Map<String, Class<? extends DomainEvent>> payloads = Map.of(
                "USER_ACCOUNT_REGISTERED", UserAccountRegistered.class,
                "AUTHENTICATION_SUCCEEDED", AuthenticationSucceeded.class,
                "AUTHENTICATION_FAILURES_AGGREGATED", AuthenticationFailuresAggregated.class,
                "ACCOUNT_TEMPORARILY_LOCKED", AccountTemporarilyLocked.class,
                "ACCOUNT_LOGGED_OUT", AccountLoggedOut.class,
                "ACCOUNT_PROFILE_CHANGED", AccountProfileChanged.class,
                "ACCOUNT_PASSWORD_CHANGED", AccountPasswordChanged.class,
                "TEAM_INVITATION_CREATED", TeamInvitationCreated.class,
                "TEAM_INVITATION_ACCEPTED", TeamInvitationAccepted.class,
                "TEAM_INVITATION_REVOKED", TeamInvitationRevoked.class);

        payloads.forEach((eventType, payloadType) -> assertEquals(
                componentNames(payloadType), definition(eventType).allowedSourceFields()));
        for (InvitationMembershipDisposition disposition :
                InvitationMembershipDisposition.values()) {
            assertEquals(
                    TeamInvitationMembershipResult.valueOf(disposition.name()),
                    disposition.eventResult());
        }
    }

    @Test
    void serializedPayloadsContainMetadataOnlyAndNoSensitiveCoordinates() {
        String serialized = payloads().stream()
                .map(codec::encodePayload)
                .collect(Collectors.joining("\n"));

        for (String forbiddenField : List.of(
                "password",
                "passwordHash",
                "sessionId",
                "cookie",
                "csrf",
                "token",
                "tokenDigest",
                "targetEmail",
                "rawError",
                "failureReason")) {
            assertFalse(serialized.contains("\"" + forbiddenField + "\""), forbiddenField);
        }
        assertFalse(serialized.contains("admin@example.com"));
        assertFalse(serialized.contains("Authorization: Bearer"));
        assertFalse(serialized.contains("invite-secret"));
        assertTrue(serialized.contains("\"credentialVersion\":2"));
        assertTrue(serialized.contains("\"emailChanged\":true"));
    }

    @Test
    void auditSummariesExposeOnlyReviewedLowCardinalityFacts() {
        var authentication = definition("AUTHENTICATION_FAILURES_AGGREGATED")
                .projectSummary(Map.of(
                        "failureClass", "INVALID_CREDENTIALS",
                        "occurrenceCount", "10",
                        "aggregationWindowSeconds", "900"));
        var profile = definition("ACCOUNT_PROFILE_CHANGED")
                .projectSummary(Map.of(
                        "usernameChanged", "false",
                        "mailChanged", "true",
                        "displayNameChanged", "false"));
        var invitation = definition("TEAM_INVITATION_ACCEPTED")
                .projectSummary(Map.of(
                        "targetRole", "MEMBER", "membershipResult", "REUSED"));

        assertEquals("INVALID_CREDENTIALS", authentication.values().get("failureClass"));
        assertEquals("true", profile.values().get("mailChanged"));
        assertFalse(profile.values().containsKey("emailChanged"));
        assertEquals("REUSED", invitation.values().get("membershipResult"));
    }

    @Test
    void auditSummaryBoundaryRejectsUnknownAndSensitiveValues() {
        AuditEventTypeDefinition definition = definition("AUTHENTICATION_SUCCEEDED");
        Map<String, String> valid = Map.of(
                "provider", "local",
                "upgradeApplied", "false",
                "securityVersion", "1");

        assertEquals(3, definition.projectSummary(valid).values().size());
        assertThrows(
                DomainValidationException.class,
                () -> definition.projectSummary(Map.of(
                        "provider", "local",
                        "upgradeApplied", "false",
                        "securityVersion", "1",
                        "sessionId", "opaque")));
        assertThrows(
                DomainValidationException.class,
                () -> definition.projectSummary(Map.of(
                        "provider", "admin@example.com",
                        "upgradeApplied", "false",
                        "securityVersion", "1")));
        assertThrows(
                DomainValidationException.class,
                () -> definition.projectSummary(Map.of(
                        "provider", "Authorization: Bearer secret",
                        "upgradeApplied", "false",
                        "securityVersion", "1")));
    }

    private void assertDefinition(
            String eventType, AuditEventCategory category, AuditOutcome outcome) {
        AuditEventTypeDefinition definition = definition(eventType);
        assertEquals(category, definition.category());
        assertEquals(outcome, definition.outcome());
        assertEquals(AuditRetentionLevel.EXTENDED, definition.retentionLevel());
    }

    private AuditEventTypeDefinition definition(String eventType) {
        return registry.find(EventType.from(eventType), SchemaVersion.V1).orElseThrow();
    }

    private static Set<String> componentNames(Class<?> payloadType) {
        return Arrays.stream(payloadType.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static List<DomainEvent> payloads() {
        UUID accountId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        return List.of(
                new UserAccountRegistered(AccountRegistrationSource.OPEN, PlatformRole.USER),
                new AuthenticationSucceeded("local", true, 2),
                new AuthenticationFailuresAggregated(
                        AuthenticationFailureClass.INVALID_CREDENTIALS, 10, 900),
                new AccountTemporarilyLocked(10, 900),
                new AccountLoggedOut(AccountLogoutScope.CURRENT_SESSION, 2),
                new AccountProfileChanged(false, true, false),
                new AccountPasswordChanged(2, 3),
                new TeamInvitationCreated(
                        BuiltInTeamRole.MEMBER,
                        true,
                        UtcTimestamp.from(Instant.parse("2026-09-04T09:00:00Z"))),
                new TeamInvitationAccepted(
                        accountId,
                        memberId,
                        BuiltInTeamRole.MEMBER,
                        TeamInvitationMembershipResult.REUSED),
                new TeamInvitationRevoked(BuiltInTeamRole.AUDITOR, false));
    }
}
