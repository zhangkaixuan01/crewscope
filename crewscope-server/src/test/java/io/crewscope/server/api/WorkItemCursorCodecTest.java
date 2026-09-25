package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.workitem.WorkItemCursor;
import io.crewscope.application.workitem.WorkItemCursorExpiredException;
import io.crewscope.application.workitem.WorkItemCursorScope;
import io.crewscope.application.workitem.WorkItemFilter;
import io.crewscope.application.workitem.WorkItemSort;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemStatus;
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
import org.junit.jupiter.api.Test;

/**
 * Signed scope, expiry and legacy-version proof for the M9b-A06 WorkItem keyset cursor: a token is
 * only replayable on the member, sort and filter that produced it, and only while fresh.
 */
class WorkItemCursorCodecTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final WorkProjectId PROJECT_ID = WorkProjectId.generate();
    private static final PrincipalId VIEWER_ID = PrincipalId.generate();
    private static final Instant ISSUED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final String KEY_1 = key(11);
    private static final String KEY_2 = key(53);

    @Test
    void roundTripsTheCompletePositionAndScope() {
        WorkItemCursorScope scope = scope(WorkItemSort.UPDATED_AT, WorkItemFilter.ALL);
        WorkItemCursor source =
                new WorkItemCursor(
                        scope,
                        Optional.of(UtcTimestamp.parse("2026-08-07T12:34:56.123456Z")),
                        OptionalInt.empty(),
                        WorkItemId.from("01989ee2-f6b0-7cda-97c4-1b337043d402"));
        WorkItemCursorCodec codec = codec("k1", Map.of("k1", KEY_1), ISSUED_AT);

        String token = codec.encode(source);

        assertEquals(source, codec.decode(token, scope));
        assertEquals(token, codec.encode(codec.decode(token, scope)));
    }

    /** The DUE_AT traversal signals the trailing null-due-time segment by carrying no component. */
    @Test
    void roundTripsADueAtCursorInsideTheNullSegment() {
        WorkItemCursorScope scope = scope(WorkItemSort.DUE_AT, WorkItemFilter.ALL);
        WorkItemCursor source =
                new WorkItemCursor(
                        scope, Optional.empty(), OptionalInt.empty(), WorkItemId.generate());
        WorkItemCursorCodec codec = codec("k1", Map.of("k1", KEY_1), ISSUED_AT);

        assertEquals(source, codec.decode(codec.encode(source), scope));
    }

    @Test
    void rejectsTamperingCrossScopeSortFilterAndLegacyTokens() {
        WorkItemCursorCodec codec = codec("k1", Map.of("k1", KEY_1), ISSUED_AT);
        WorkItemCursor cursor =
                new WorkItemCursor(
                        scope(WorkItemSort.UPDATED_AT, WorkItemFilter.ALL),
                        Optional.of(UtcTimestamp.parse("2026-08-07T12:34:56Z")),
                        OptionalInt.empty(),
                        WorkItemId.generate());
        String token = codec.encode(cursor);
        char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';

        assertInvalid(() -> codec.decode(
                token.substring(0, token.length() - 1) + replacement,
                scope(WorkItemSort.UPDATED_AT, WorkItemFilter.ALL)));
        assertInvalid(() -> codec.decode(token, scope(WorkItemSort.PRIORITY, WorkItemFilter.ALL)));
        assertInvalid(() -> codec.decode(token, scope(WorkItemSort.CREATED_AT, WorkItemFilter.ALL)));
        assertInvalid(() -> codec.decode(
                token, scope(WorkItemSort.UPDATED_AT,
                        WorkItemFilter.ofStatus(WorkItemStatus.BACKLOG))));
        // A v1 token predates scope binding and never survives the version gate.
        assertInvalid(() -> codec.decode(
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] {1, 1, 'k'}),
                scope(WorkItemSort.UPDATED_AT, WorkItemFilter.ALL)));
        byte[] unknownVersion = Base64.getUrlDecoder().decode(
                codec.encode(new WorkItemCursor(
                        scope(WorkItemSort.UPDATED_AT, WorkItemFilter.ALL),
                        Optional.of(UtcTimestamp.parse("2026-08-07T12:34:56Z")),
                        OptionalInt.empty(),
                        WorkItemId.generate())));
        unknownVersion[0] = 3;
        assertInvalid(() -> codec.decode(
                Base64.getUrlEncoder().withoutPadding().encodeToString(unknownVersion),
                scope(WorkItemSort.UPDATED_AT, WorkItemFilter.ALL)));
    }

    @Test
    void reportsExpiredCursorsInsteadOfAcceptingThem() {
        WorkItemCursorScope scope = scope(WorkItemSort.UPDATED_AT, WorkItemFilter.ALL);
        WorkItemCursor cursor =
                new WorkItemCursor(
                        scope,
                        Optional.of(UtcTimestamp.parse("2026-08-07T12:34:56Z")),
                        OptionalInt.empty(),
                        WorkItemId.generate());
        String token = codec("k1", Map.of("k1", KEY_1), ISSUED_AT).encode(cursor);
        Duration maximumAge = Duration.ofMinutes(30);

        assertEquals(
                cursor,
                codec("k1", Map.of("k1", KEY_1), ISSUED_AT.plus(maximumAge).minusSeconds(60))
                        .decode(token, scope));
        assertThrows(
                WorkItemCursorExpiredException.class,
                () -> codec("k1", Map.of("k1", KEY_1), ISSUED_AT.plus(maximumAge).plusSeconds(60))
                        .decode(token, scope));
        // An issue time too far in the future is invalid, not merely unexpired.
        assertInvalid(() -> codec("k1", Map.of("k1", KEY_1), ISSUED_AT.minus(Duration.ofSeconds(31)))
                .decode(token, scope));
    }

    @Test
    void acceptsRetainedRotationKeysAndRejectsRemovedKeys() {
        WorkItemCursorScope scope = scope(WorkItemSort.UPDATED_AT, WorkItemFilter.ALL);
        WorkItemCursor cursor =
                new WorkItemCursor(
                        scope,
                        Optional.of(UtcTimestamp.parse("2026-08-07T12:34:56Z")),
                        OptionalInt.empty(),
                        WorkItemId.generate());
        String oldToken = codec("k1", Map.of("k1", KEY_1), ISSUED_AT).encode(cursor);
        LinkedHashMap<String, String> rotating = new LinkedHashMap<>();
        rotating.put("k1", KEY_1);
        rotating.put("k2", KEY_2);

        assertEquals(
                cursor, codec("k2", rotating, ISSUED_AT).decode(oldToken, scope));
        assertInvalid(() -> codec("k2", Map.of("k2", KEY_2), ISSUED_AT).decode(oldToken, scope));
    }

    @Test
    void appliesTheWorkItemPageLimitContract() {
        assertEquals(20, ApiPagination.workItemLimit(null));
        assertEquals(1, ApiPagination.workItemLimit(1));
        assertEquals(100, ApiPagination.workItemLimit(100));
        assertThrows(ApiRequestException.class, () -> ApiPagination.workItemLimit(0));
        assertThrows(ApiRequestException.class, () -> ApiPagination.workItemLimit(101));
    }

    private static WorkItemCursorScope scope(WorkItemSort sort, WorkItemFilter filter) {
        return WorkItemCursorScope.of(
                ORGANIZATION_ID, TEAM_ID, PROJECT_ID, VIEWER_ID, sort, filter);
    }

    private static WorkItemCursorCodec codec(
            String currentKeyId, Map<String, String> keys, Instant now) {
        return new WorkItemCursorCodec(
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
