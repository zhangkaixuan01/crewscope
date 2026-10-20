package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.organization.OrganizationMemberDirectoryCursor;
import io.crewscope.application.organization.OrganizationMemberDirectoryCursorExpiredException;
import io.crewscope.application.organization.OrganizationMemberDirectoryFilterFingerprint;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Signed scope, expiry and cross-domain proof for the M13b-A01 organization member directory
 * cursor: a continuation only replays on the viewer and filter that produced it, and only while
 * fresh. The sort key may carry a full display name, including multi-byte ones.
 */
class OrganizationMemberCursorCodecTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final PrincipalId VIEWER_ID = PrincipalId.generate();
    private static final Instant ISSUED_AT = Instant.parse("2026-10-19T08:00:00Z");
    private static final String KEY_1 = key(7);
    private static final String KEY_2 = key(41);
    private static final OrganizationMemberDirectoryFilterFingerprint FINGERPRINT =
            new OrganizationMemberDirectoryFilterFingerprint("ab".repeat(32));

    @Test
    void roundTripsTheSortKeyTailIncludingMultiByteNames() {
        OrganizationMemberCursorCodec codec = codec("k1", Map.of("k1", KEY_1), ISSUED_AT);
        OrganizationMemberDirectoryCursor position =
                new OrganizationMemberDirectoryCursor("评审助理-乙", PrincipalId.generate());

        OrganizationMemberDirectoryCursor decoded =
                codec.decode(codec.encode(cursor(scope(), position)), scope());

        assertEquals(position, decoded);
    }

    @Test
    void rejectsTamperingCrossScopeViewerFilterAndForeignTokens() {
        OrganizationMemberCursorCodec codec = codec("k1", Map.of("k1", KEY_1), ISSUED_AT);
        OrganizationMemberDirectoryCursor position =
                new OrganizationMemberDirectoryCursor("alice", PrincipalId.generate());
        String token = codec.encode(cursor(scope(), position));
        char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';

        assertInvalid(() -> codec.decode(
                token.substring(0, token.length() - 1) + replacement, scope()));
        assertInvalid(() -> codec.decode(token, new OrganizationMemberCursorCodec
                .OrganizationMemberCursorScope(
                OrganizationId.generate(), VIEWER_ID, FINGERPRINT)));
        assertInvalid(() -> codec.decode(token, new OrganizationMemberCursorCodec
                .OrganizationMemberCursorScope(
                ORGANIZATION_ID, PrincipalId.generate(), FINGERPRINT)));
        assertInvalid(() -> codec.decode(token, new OrganizationMemberCursorCodec
                .OrganizationMemberCursorScope(
                ORGANIZATION_ID, VIEWER_ID,
                new OrganizationMemberDirectoryFilterFingerprint("cd".repeat(32)))));
        // A principal-directory token is signed under another domain and must never decode here.
        assertInvalid(() -> codec.decode("not-a-signed-cursor", scope()));
    }

    @Test
    void reportsExpiredCursorsInsteadOfAcceptingThem() {
        String token = codec("k1", Map.of("k1", KEY_1), ISSUED_AT)
                .encode(cursor(scope(), new OrganizationMemberDirectoryCursor("alice", PrincipalId.generate())));
        Duration maximumAge = Duration.ofMinutes(30);

        assertEquals(
                "alice",
                codec("k1", Map.of("k1", KEY_1), ISSUED_AT.plus(maximumAge).minusSeconds(60))
                        .decode(token, scope()).sortKey());
        assertThrows(
                OrganizationMemberDirectoryCursorExpiredException.class,
                () -> codec("k1", Map.of("k1", KEY_1), ISSUED_AT.plus(maximumAge).plusSeconds(60))
                        .decode(token, scope()));
        // An issue time too far in the future is invalid, not merely unexpired.
        assertInvalid(() -> codec("k1", Map.of("k1", KEY_1), ISSUED_AT.minus(Duration.ofSeconds(31)))
                .decode(token, scope()));
    }

    @Test
    void acceptsRetainedRotationKeysAndRejectsRemovedKeys() {
        String token = codec("k1", Map.of("k1", KEY_1), ISSUED_AT)
                .encode(cursor(scope(), new OrganizationMemberDirectoryCursor("alice", PrincipalId.generate())));
        LinkedHashMap<String, String> rotating = new LinkedHashMap<>();
        rotating.put("k1", KEY_1);
        rotating.put("k2", KEY_2);

        assertEquals(
                "alice",
                codec("k2", rotating, ISSUED_AT).decode(token, scope()).sortKey());
        assertInvalid(() -> codec("k2", Map.of("k2", KEY_2), ISSUED_AT).decode(token, scope()));
    }

    private static OrganizationMemberCursorCodec.OrganizationMemberCursorScope scope() {
        return new OrganizationMemberCursorCodec.OrganizationMemberCursorScope(
                ORGANIZATION_ID, VIEWER_ID, FINGERPRINT);
    }

    private static OrganizationMemberCursorCodec.OrganizationMemberCursor cursor(
            OrganizationMemberCursorCodec.OrganizationMemberCursorScope scope,
            OrganizationMemberDirectoryCursor position) {
        return new OrganizationMemberCursorCodec.OrganizationMemberCursor(scope, position);
    }

    private static OrganizationMemberCursorCodec codec(
            String currentKeyId, Map<String, String> keys, Instant now) {
        return new OrganizationMemberCursorCodec(
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
