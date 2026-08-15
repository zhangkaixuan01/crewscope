package io.crewscope.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.task.DecodedTaskToken;
import io.crewscope.application.task.TaskTokenScopeFingerprint;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Signature, key rotation, exact time and redaction coverage for Task Token JWTs. */
class NimbusTaskTokenCodecTest {

    private static final String OLD_KEY = encoded((byte) 7);
    private static final String NEW_KEY = encoded((byte) 9);

    @Test
    void roundTripsMinimumEnvelopeAndNeverPrintsSecrets() {
        TaskTokenSecurityFixture fixture = new TaskTokenSecurityFixture();
        NimbusTaskTokenCodec codec = new NimbusTaskTokenCodec(
                "crewscope", "v1", Map.of("v1", OLD_KEY));

        String token = codec.encode(fixture.claims);
        DecodedTaskToken decoded = codec.decode(token);

        assertEquals(fixture.grantId, decoded.grantId());
        assertEquals(fixture.principalId, decoded.subject());
        assertEquals(fixture.organizationId, decoded.organizationId());
        assertEquals(fixture.environment, decoded.environment());
        assertEquals(fixture.issuedAt, decoded.issuedAt());
        assertEquals(fixture.expiresAt, decoded.expiresAt());
        assertEquals(TaskTokenScopeFingerprint.compute(fixture.scope), decoded.scopeFingerprint());
        assertFalse(decoded.toString().contains(fixture.jti.reveal()));
        assertFalse(codec.toString().contains(OLD_KEY));
    }

    @Test
    void rejectsTamperingAndUnknownSigningKeys() {
        TaskTokenSecurityFixture fixture = new TaskTokenSecurityFixture();
        NimbusTaskTokenCodec oldCodec = new NimbusTaskTokenCodec(
                "crewscope", "old", Map.of("old", OLD_KEY));
        String token = oldCodec.encode(fixture.claims);
        String[] segments = token.split("\\.", -1);
        String signature = segments[2];
        String tamperedSignature = (signature.startsWith("A") ? "B" : "A")
                + signature.substring(1);
        String tampered = segments[0] + "." + segments[1] + "." + tamperedSignature;

        assertThrows(RuntimeException.class, () -> oldCodec.decode(tampered));
        NimbusTaskTokenCodec newOnly = new NimbusTaskTokenCodec(
                "crewscope", "new", Map.of("new", NEW_KEY));
        assertThrows(RuntimeException.class, () -> newOnly.decode(token));
    }

    @Test
    void verifiesOldTokensWhileRotatingTheCurrentSigningKey() {
        TaskTokenSecurityFixture fixture = new TaskTokenSecurityFixture();
        String oldToken = new NimbusTaskTokenCodec(
                "crewscope", "old", Map.of("old", OLD_KEY)).encode(fixture.claims);
        NimbusTaskTokenCodec rotated = new NimbusTaskTokenCodec(
                "crewscope", "new", Map.of("old", OLD_KEY, "new", NEW_KEY));

        assertEquals(fixture.grantId, rotated.decode(oldToken).grantId());
        String newToken = rotated.encode(fixture.claims);
        assertTrue(newToken.length() > 100);
        assertEquals(fixture.grantId, rotated.decode(newToken).grantId());
    }

    @Test
    void failsClosedForShortOrMissingCurrentKeys() {
        assertThrows(IllegalArgumentException.class, () -> new NimbusTaskTokenCodec(
                "crewscope", "v1", Map.of("v1", Base64.getEncoder().encodeToString(new byte[16]))));
        assertThrows(IllegalArgumentException.class, () -> new NimbusTaskTokenCodec(
                "crewscope", "missing", Map.of("v1", OLD_KEY)));
    }

    private static String encoded(byte fill) {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, fill);
        return Base64.getEncoder().encodeToString(value);
    }
}
