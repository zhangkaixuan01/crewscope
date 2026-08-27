package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.audit.AuditCursor;
import io.crewscope.application.audit.AuditCursorScope;
import io.crewscope.application.audit.AuditQueryFilter;
import io.crewscope.domain.audit.AuditEventCategory;
import io.crewscope.domain.audit.AuditEventId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Signed scope and rotation proof for the M6-A03 Audit keyset cursor. */
class AuditCursorCodecM6A03Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final String KEY_1 = key(11);
    private static final String KEY_2 = key(53);

    @Test
    void roundTripsTheCompleteAuditPositionAndScope() {
        AuditCursor source = cursor(AuditQueryFilter.ALL);
        AuditCursorCodec codec = codec("k1", Map.of("k1", KEY_1));

        assertEquals(
                source,
                codec.decode(
                        codec.encode(source),
                        ORGANIZATION_ID,
                        TEAM_ID,
                        AuditQueryFilter.ALL));
    }

    @Test
    void rejectsTamperingCrossScopeAndFilterReplay() {
        AuditCursorCodec codec = codec("k1", Map.of("k1", KEY_1));
        String token = codec.encode(cursor(AuditQueryFilter.ALL));
        char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;
        AuditQueryFilter filtered = new AuditQueryFilter(
                Optional.empty(),
                Optional.empty(),
                Set.of(AuditEventCategory.SECURITY),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        assertInvalid(() -> codec.decode(
                tampered, ORGANIZATION_ID, TEAM_ID, AuditQueryFilter.ALL));
        assertInvalid(() -> codec.decode(
                token, OrganizationId.generate(), TEAM_ID, AuditQueryFilter.ALL));
        assertInvalid(() -> codec.decode(
                token, ORGANIZATION_ID, TeamId.generate(), AuditQueryFilter.ALL));
        assertInvalid(() -> codec.decode(token, ORGANIZATION_ID, TEAM_ID, filtered));
    }

    @Test
    void acceptsRetainedRotationKeysAndRejectsRemovedKeys() {
        AuditCursor source = cursor(AuditQueryFilter.ALL);
        String oldToken = codec("k1", Map.of("k1", KEY_1)).encode(source);
        LinkedHashMap<String, String> rotating = new LinkedHashMap<>();
        rotating.put("k1", KEY_1);
        rotating.put("k2", KEY_2);

        assertEquals(
                source,
                codec("k2", rotating).decode(
                        oldToken, ORGANIZATION_ID, TEAM_ID, AuditQueryFilter.ALL));
        assertInvalid(() -> codec("k2", Map.of("k2", KEY_2)).decode(
                oldToken, ORGANIZATION_ID, TEAM_ID, AuditQueryFilter.ALL));
    }

    private static AuditCursor cursor(AuditQueryFilter filter) {
        return new AuditCursor(
                AuditCursorScope.of(ORGANIZATION_ID, TEAM_ID, filter),
                UtcTimestamp.parse("2026-08-27T03:00:00.123456Z"),
                AuditEventId.generate());
    }

    private static AuditCursorCodec codec(String currentKeyId, Map<String, String> keys) {
        return new AuditCursorCodec(new TeamActivityCursorKeyRing(currentKeyId, keys));
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
