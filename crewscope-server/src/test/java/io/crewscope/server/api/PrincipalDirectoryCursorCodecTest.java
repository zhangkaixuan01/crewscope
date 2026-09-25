package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.principal.PrincipalDirectoryCursor;
import io.crewscope.application.principal.PrincipalDirectoryCursorExpiredException;
import io.crewscope.application.principal.PrincipalDirectoryFilterFingerprint;
import io.crewscope.application.principal.PrincipalDirectoryPurpose;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Signed scope, expiry and cross-domain proof for the M9b-A06 principal directory cursor: a
 * continuation only replays on the viewer, purpose and filter that produced it, and only while
 * fresh. The sort key may carry a full display name, including multi-byte ones.
 */
class PrincipalDirectoryCursorCodecTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final PrincipalId VIEWER_ID = PrincipalId.generate();
    private static final Instant ISSUED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final String KEY_1 = key(7);
    private static final String KEY_2 = key(41);
    private static final PrincipalDirectoryFilterFingerprint FINGERPRINT =
            new PrincipalDirectoryFilterFingerprint("ab".repeat(32));

    @Test
    void roundTripsTheSortKeyTailIncludingMultiByteNames() {
        PrincipalDirectoryCursorCodec codec = codec("k1", Map.of("k1", KEY_1), ISSUED_AT);
        PrincipalDirectoryCursor position =
                new PrincipalDirectoryCursor("评审助理-乙", PrincipalId.generate());

        PrincipalDirectoryCursor decoded =
                codec.decode(codec.encode(scope(), position), scope());

        assertEquals(position, decoded);
    }

    @Test
    void rejectsTamperingCrossScopePurposeFilterAndForeignTokens() {
        PrincipalDirectoryCursorCodec codec = codec("k1", Map.of("k1", KEY_1), ISSUED_AT);
        PrincipalDirectoryCursor position =
                new PrincipalDirectoryCursor("alice", PrincipalId.generate());
        String token = codec.encode(scope(), position);
        char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';

        assertInvalid(() -> codec.decode(
                token.substring(0, token.length() - 1) + replacement, scope()));
        assertInvalid(() -> codec.decode(token, new PrincipalDirectoryCursorCodec.PrincipalDirectoryCursorScope(
                OrganizationId.generate(), TEAM_ID, VIEWER_ID,
                PrincipalDirectoryPurpose.ASSIGNMENT, FINGERPRINT)));
        assertInvalid(() -> codec.decode(token, new PrincipalDirectoryCursorCodec.PrincipalDirectoryCursorScope(
                ORGANIZATION_ID, TeamId.generate(), VIEWER_ID,
                PrincipalDirectoryPurpose.ASSIGNMENT, FINGERPRINT)));
        assertInvalid(() -> codec.decode(token, new PrincipalDirectoryCursorCodec.PrincipalDirectoryCursorScope(
                ORGANIZATION_ID, TEAM_ID, PrincipalId.generate(),
                PrincipalDirectoryPurpose.ASSIGNMENT, FINGERPRINT)));
        // The AUDIT purpose resolves a different visible set, so it is another scope entirely.
        assertInvalid(() -> codec.decode(token, new PrincipalDirectoryCursorCodec.PrincipalDirectoryCursorScope(
                ORGANIZATION_ID, TEAM_ID, VIEWER_ID,
                PrincipalDirectoryPurpose.AUDIT, FINGERPRINT)));
        assertInvalid(() -> codec.decode(token, new PrincipalDirectoryCursorCodec.PrincipalDirectoryCursorScope(
                ORGANIZATION_ID, TEAM_ID, VIEWER_ID,
                PrincipalDirectoryPurpose.ASSIGNMENT,
                new PrincipalDirectoryFilterFingerprint("cd".repeat(32)))));
        // A WorkDesk token is signed under another domain and must never decode here.
        assertInvalid(() -> codec.decode("not-a-signed-cursor", scope()));
    }

    @Test
    void reportsExpiredCursorsInsteadOfAcceptingThem() {
        String token = codec("k1", Map.of("k1", KEY_1), ISSUED_AT)
                .encode(scope(), new PrincipalDirectoryCursor("alice", PrincipalId.generate()));
        Duration maximumAge = Duration.ofMinutes(30);

        assertEquals(
                "alice",
                codec("k1", Map.of("k1", KEY_1), ISSUED_AT.plus(maximumAge).minusSeconds(60))
                        .decode(token, scope()).sortKey());
        assertThrows(
                PrincipalDirectoryCursorExpiredException.class,
                () -> codec("k1", Map.of("k1", KEY_1), ISSUED_AT.plus(maximumAge).plusSeconds(60))
                        .decode(token, scope()));
        // An issue time too far in the future is invalid, not merely unexpired.
        assertInvalid(() -> codec("k1", Map.of("k1", KEY_1), ISSUED_AT.minus(Duration.ofSeconds(31)))
                .decode(token, scope()));
    }

    @Test
    void acceptsRetainedRotationKeysAndRejectsRemovedKeys() {
        String token = codec("k1", Map.of("k1", KEY_1), ISSUED_AT)
                .encode(scope(), new PrincipalDirectoryCursor("alice", PrincipalId.generate()));
        LinkedHashMap<String, String> rotating = new LinkedHashMap<>();
        rotating.put("k1", KEY_1);
        rotating.put("k2", KEY_2);

        assertEquals(
                "alice",
                codec("k2", rotating, ISSUED_AT).decode(token, scope()).sortKey());
        assertInvalid(() -> codec("k2", Map.of("k2", KEY_2), ISSUED_AT).decode(token, scope()));
    }

    private static PrincipalDirectoryCursorCodec.PrincipalDirectoryCursorScope scope() {
        return new PrincipalDirectoryCursorCodec.PrincipalDirectoryCursorScope(
                ORGANIZATION_ID, TEAM_ID, VIEWER_ID,
                PrincipalDirectoryPurpose.ASSIGNMENT, FINGERPRINT);
    }

    private static PrincipalDirectoryCursorCodec codec(
            String currentKeyId, Map<String, String> keys, Instant now) {
        return new PrincipalDirectoryCursorCodec(
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
