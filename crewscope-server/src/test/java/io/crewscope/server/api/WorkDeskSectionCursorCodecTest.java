package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.workdesk.WorkDeskCursorExpiredException;
import io.crewscope.application.workdesk.WorkDeskSectionPosition;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Signed scope, expiry and cross-domain proof for the M9b-A06 WorkDesk section cursor: a token is
 * only replayable on the member, section and filter that produced it, and only while fresh.
 */
class WorkDeskSectionCursorCodecTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final PrincipalId VIEWER_ID = PrincipalId.generate();
    private static final Instant ISSUED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final String KEY_1 = key(11);
    private static final String KEY_2 = key(53);

    @Test
    void roundTripsBothPositionShapes() {
        WorkDeskSectionCursorCodec.WorkDeskSectionCursorScope scope = scope("WORK_ITEM");
        WorkDeskSectionCursorCodec.WorkDeskSectionCursor source =
                new WorkDeskSectionCursorCodec.WorkDeskSectionCursor(
                        scope,
                        WorkDeskSectionPosition.of(
                                "WORK_ITEM",
                                Instant.parse("2026-08-07T12:34:56.123456Z"),
                                UUID.fromString("01989ee2-f6b0-7cda-97c4-1b337043d402")));
        WorkDeskSectionCursorCodec codec = codec("k1", Map.of("k1", KEY_1), ISSUED_AT);

        String token = codec.encode(source);

        assertEquals(source, codec.decode(token, scope));
        assertEquals(token, codec.encode(codec.decode(token, scope)));

        // The Inbox-shaped position carries all four keys, deadline included.
        WorkDeskSectionCursorCodec.WorkDeskSectionCursorScope inboxScope = scope("INBOX");
        WorkDeskSectionCursorCodec.WorkDeskSectionCursor inbox =
                new WorkDeskSectionCursorCodec.WorkDeskSectionCursor(
                        inboxScope,
                        WorkDeskSectionPosition.inbox(
                                4,
                                Optional.of(UtcTimestamp.parse("2026-08-08T09:00:00Z")),
                                Instant.parse("2026-08-07T12:34:56Z"),
                                UUID.randomUUID()));
        assertEquals(inbox, codec.decode(codec.encode(inbox), inboxScope));
    }

    @Test
    void rejectsTamperingCrossScopeSectionFilterAndForeignTokens() {
        WorkDeskSectionCursorCodec codec = codec("k1", Map.of("k1", KEY_1), ISSUED_AT);
        String token = codec.encode(new WorkDeskSectionCursorCodec.WorkDeskSectionCursor(
                scope("REVIEW"),
                WorkDeskSectionPosition.of(
                        "REVIEW", Instant.parse("2026-08-07T12:34:56Z"), UUID.randomUUID())));
        char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';

        assertInvalid(() -> codec.decode(
                token.substring(0, token.length() - 1) + replacement, scope("REVIEW")));
        assertInvalid(() -> codec.decode(token, scope("HUMAN_GATE")));
        assertInvalid(() -> codec.decode(token, scope("WORK_ITEM")));
        assertInvalid(() -> codec.decode(
                token,
                new WorkDeskSectionCursorCodec.WorkDeskSectionCursorScope(
                        ORGANIZATION_ID, TEAM_ID, VIEWER_ID, "REVIEW",
                        WorkDeskApiSupport.fingerprint(
                                Optional.of(WorkProjectId.generate()), Optional.empty(), false))));
        assertInvalid(() -> codec.decode(
                token,
                new WorkDeskSectionCursorCodec.WorkDeskSectionCursorScope(
                        ORGANIZATION_ID, TEAM_ID, PrincipalId.generate(), "REVIEW",
                        WorkDeskApiSupport.fingerprint(Optional.empty(), Optional.empty(), false))));
        // A WorkItem-list token is signed under another domain and must never decode here.
        WorkItemCursorCodec foreign = new WorkItemCursorCodec(
                new TeamActivityCursorKeyRing("k1", Map.of("k1", KEY_1)),
                Clock.fixed(ISSUED_AT, ZoneOffset.UTC),
                Duration.ofMinutes(30));
        assertInvalid(() -> codec.decode(
                foreign.encode(new io.crewscope.application.workitem.WorkItemCursor(
                        io.crewscope.application.workitem.WorkItemCursorScope.of(
                                ORGANIZATION_ID, TEAM_ID, WorkProjectId.generate(), VIEWER_ID,
                                io.crewscope.application.workitem.WorkItemSort.UPDATED_AT,
                                io.crewscope.application.workitem.WorkItemFilter.ALL),
                        Optional.of(UtcTimestamp.parse("2026-08-07T12:34:56Z")),
                        OptionalInt.empty(),
                        io.crewscope.domain.workitem.WorkItemId.generate())),
                scope("REVIEW")));
    }

    @Test
    void reportsExpiredCursorsInsteadOfAcceptingThem() {
        WorkDeskSectionCursorCodec.WorkDeskSectionCursorScope scope = scope("WORK_ITEM");
        WorkDeskSectionCursorCodec.WorkDeskSectionCursor cursor =
                new WorkDeskSectionCursorCodec.WorkDeskSectionCursor(
                        scope,
                        WorkDeskSectionPosition.of(
                                "WORK_ITEM", Instant.parse("2026-08-07T12:34:56Z"),
                                UUID.randomUUID()));
        String token = codec("k1", Map.of("k1", KEY_1), ISSUED_AT).encode(cursor);
        Duration maximumAge = Duration.ofMinutes(30);

        assertEquals(
                cursor,
                codec("k1", Map.of("k1", KEY_1), ISSUED_AT.plus(maximumAge).minusSeconds(60))
                        .decode(token, scope));
        assertThrows(
                WorkDeskCursorExpiredException.class,
                () -> codec("k1", Map.of("k1", KEY_1), ISSUED_AT.plus(maximumAge).plusSeconds(60))
                        .decode(token, scope));
        // An issue time too far in the future is invalid, not merely unexpired.
        assertInvalid(() -> codec("k1", Map.of("k1", KEY_1), ISSUED_AT.minus(Duration.ofSeconds(31)))
                .decode(token, scope));
    }

    @Test
    void acceptsRetainedRotationKeysAndRejectsRemovedKeys() {
        WorkDeskSectionCursorCodec.WorkDeskSectionCursorScope scope = scope("WORK_ITEM");
        WorkDeskSectionCursorCodec.WorkDeskSectionCursor cursor =
                new WorkDeskSectionCursorCodec.WorkDeskSectionCursor(
                        scope,
                        WorkDeskSectionPosition.of(
                                "WORK_ITEM", Instant.parse("2026-08-07T12:34:56Z"),
                                UUID.randomUUID()));
        String oldToken = codec("k1", Map.of("k1", KEY_1), ISSUED_AT).encode(cursor);
        LinkedHashMap<String, String> rotating = new LinkedHashMap<>();
        rotating.put("k1", KEY_1);
        rotating.put("k2", KEY_2);

        assertEquals(cursor, codec("k2", rotating, ISSUED_AT).decode(oldToken, scope));
        assertInvalid(() -> codec("k2", Map.of("k2", KEY_2), ISSUED_AT).decode(oldToken, scope));
    }

    @Test
    void appliesTheWorkDeskSectionLimitContract() {
        assertEquals(20, ApiPagination.workDeskSectionLimit(null));
        assertEquals(1, ApiPagination.workDeskSectionLimit(1));
        assertEquals(100, ApiPagination.workDeskSectionLimit(100));
        assertThrows(ApiRequestException.class, () -> ApiPagination.workDeskSectionLimit(0));
        assertThrows(ApiRequestException.class, () -> ApiPagination.workDeskSectionLimit(101));
    }

    @Test
    void bindsTheFingerprintToEveryFilterDimension() {
        // Any changed filter dimension produces another fingerprint, so the same position cannot
        // silently continue a differently filtered section.
        assertEquals(
                WorkDeskApiSupport.fingerprint(Optional.empty(), Optional.empty(), false),
                WorkDeskApiSupport.fingerprint(Optional.empty(), Optional.empty(), false));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                WorkDeskApiSupport.fingerprint(Optional.empty(), Optional.empty(), false),
                WorkDeskApiSupport.fingerprint(Optional.empty(), Optional.empty(), true));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                WorkDeskApiSupport.fingerprint(Optional.empty(), Optional.empty(), false),
                WorkDeskApiSupport.fingerprint(
                        Optional.empty(), Optional.of(ResponsibilityRole.REVIEWER), false));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                WorkDeskApiSupport.fingerprint(Optional.empty(), Optional.empty(), false),
                WorkDeskApiSupport.fingerprint(
                        Optional.of(WorkProjectId.generate()), Optional.empty(), false));
    }

    private static WorkDeskSectionCursorCodec.WorkDeskSectionCursorScope scope(String sectionKey) {
        return new WorkDeskSectionCursorCodec.WorkDeskSectionCursorScope(
                ORGANIZATION_ID, TEAM_ID, VIEWER_ID, sectionKey,
                WorkDeskApiSupport.fingerprint(Optional.empty(), Optional.empty(), false));
    }

    private static WorkDeskSectionCursorCodec codec(
            String currentKeyId, Map<String, String> keys, Instant now) {
        return new WorkDeskSectionCursorCodec(
                new TeamActivityCursorKeyRing(currentKeyId, keys),
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofMinutes(30));
    }

    private static String key(int seed) {
        byte[] value = new byte[32];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (seed + index);
        }
        return Base64.getEncoder().encodeToString(value);
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
        ApiRequestException failure = assertThrows(ApiRequestException.class, executable);
        assertEquals("invalid_cursor", failure.code());
    }
}
