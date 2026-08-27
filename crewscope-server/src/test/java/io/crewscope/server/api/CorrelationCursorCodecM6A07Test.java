package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.correlation.CorrelationCursor;
import io.crewscope.application.correlation.CorrelationEventSource;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Correlation, tenant, tamper and key-rotation binding proof for M6-A07. */
class CorrelationCursorCodecM6A07Test {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final UUID correlationId = UUID.randomUUID();

    @Test
    void roundTripsTheFullScopeAndRejectsReplayOrTampering() {
        CorrelationCursor cursor = new CorrelationCursor(
                organizationId, teamId, correlationId,
                UtcTimestamp.parse("2026-08-27T06:00:00.123456Z"), UUID.randomUUID(),
                CorrelationEventSource.DOMAIN_EVENT);
        CorrelationCursorCodec codec = codec("k1", key(19));
        String token = codec.encode(cursor);

        assertEquals(cursor, codec.decode(token, organizationId, teamId, correlationId));
        assertInvalid(() -> codec.decode(token, organizationId, teamId, UUID.randomUUID()));
        assertInvalid(() -> codec.decode(token, organizationId, TeamId.generate(), correlationId));
        char replacement = token.endsWith("A") ? 'B' : 'A';
        assertInvalid(() -> codec.decode(
                token.substring(0, token.length() - 1) + replacement,
                organizationId, teamId, correlationId));
    }

    private static CorrelationCursorCodec codec(String keyId, String key) {
        return new CorrelationCursorCodec(
                new TeamActivityCursorKeyRing(keyId, Map.of(keyId, key)));
    }

    private static String key(int seed) {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (seed + index);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
        assertEquals("invalid_cursor", assertThrows(ApiRequestException.class, executable).code());
    }
}
