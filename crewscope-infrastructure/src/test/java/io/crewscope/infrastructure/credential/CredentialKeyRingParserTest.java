package io.crewscope.infrastructure.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Proves strict, fail-closed parsing and in-memory redaction of credential key rings. */
class CredentialKeyRingParserTest {

    private static final String CURRENT_KEY_ID = "credential-key-2026-08";
    private static final String HISTORICAL_KEY_ID = "credential-key-2026-07";

    private final CredentialKeyRingParser parser = new CredentialKeyRingParser();

    @Test
    void parsesCurrentAndHistoricalAes256Keys() {
        byte[] current = keyBytes((byte) 0x31);
        byte[] historical = keyBytes((byte) 0x22);
        String encoded = entry(CURRENT_KEY_ID, current) + ";"
                + entry(HISTORICAL_KEY_ID, historical);

        try (CredentialKeyRing keyRing = parser.parse(CURRENT_KEY_ID, encoded)) {
            assertEquals(CURRENT_KEY_ID, keyRing.currentKeyId());
            assertEquals(2, keyRing.keyIds().size());
            assertArrayEquals(current, keyRing.currentKey().copyKeyBytes());
            assertArrayEquals(
                    historical,
                    keyRing.find(HISTORICAL_KEY_ID).orElseThrow().copyKeyBytes());
        }
    }

    @Test
    void redactsEncodedAndDecodedKeyMaterialFromStringRepresentations() {
        byte[] key = keyBytes((byte) 0x5a);
        String base64 = Base64.getEncoder().encodeToString(key);

        try (CredentialKeyRing keyRing = parser.parse(
                CURRENT_KEY_ID, CURRENT_KEY_ID + "=" + base64)) {
            String ringText = keyRing.toString();
            String keyText = keyRing.currentKey().toString();

            assertFalse(ringText.contains(base64));
            assertFalse(keyText.contains(base64));
            assertFalse(ringText.contains(Arrays.toString(key)));
            assertFalse(keyText.contains(Arrays.toString(key)));
            assertTrue(ringText.contains("REDACTED"));
            assertTrue(keyText.contains("REDACTED"));
        }
    }

    @Test
    void rejectsMissingConfiguration() {
        assertSafeFailure(null, entry(CURRENT_KEY_ID, keyBytes((byte) 1)));
        assertSafeFailure(" ", entry(CURRENT_KEY_ID, keyBytes((byte) 1)));
        assertSafeFailure(CURRENT_KEY_ID, null);
        assertSafeFailure(CURRENT_KEY_ID, " ");
    }

    @Test
    void rejectsInvalidBase64WithoutEchoingItsInput() {
        String invalid = "not-valid-###-base64";

        CredentialKeyConfigurationException exception = assertThrows(
                CredentialKeyConfigurationException.class,
                () -> parser.parse(CURRENT_KEY_ID, CURRENT_KEY_ID + "=" + invalid));

        assertFalse(exception.getMessage().contains(invalid));
    }

    @Test
    void rejectsNonAes256MaterialWithoutEchoingItsInput() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        CredentialKeyConfigurationException exception = assertThrows(
                CredentialKeyConfigurationException.class,
                () -> parser.parse(CURRENT_KEY_ID, CURRENT_KEY_ID + "=" + shortKey));

        assertFalse(exception.getMessage().contains(shortKey));
    }

    @Test
    void rejectsDuplicateOrExcessiveKeyIds() {
        String duplicate = entry(CURRENT_KEY_ID, keyBytes((byte) 1)) + ";"
                + entry(CURRENT_KEY_ID, keyBytes((byte) 2));
        assertSafeFailure(CURRENT_KEY_ID, duplicate);

        StringBuilder excessive = new StringBuilder();
        for (int index = 0; index < 17; index++) {
            if (index > 0) {
                excessive.append(';');
            }
            excessive.append(entry("key-" + index, keyBytes((byte) index)));
        }
        assertSafeFailure("key-0", excessive.toString());
    }

    @Test
    void rejectsMissingCurrentKeyAndInvalidKeyIds() {
        assertSafeFailure(
                CURRENT_KEY_ID, entry(HISTORICAL_KEY_ID, keyBytes((byte) 2)));
        assertSafeFailure(
                "invalid key id", entry("invalid key id", keyBytes((byte) 3)));
        assertSafeFailure(
                CURRENT_KEY_ID, entry("../unsafe", keyBytes((byte) 4)));
    }

    @Test
    void closeIsIdempotentAndMakesKeyMaterialUnavailable() {
        CredentialKeyRing keyRing = parser.parse(
                CURRENT_KEY_ID, entry(CURRENT_KEY_ID, keyBytes((byte) 7)));
        CredentialEncryptionKey currentKey = keyRing.currentKey();

        keyRing.close();
        keyRing.close();

        assertThrows(IllegalStateException.class, currentKey::copyKeyBytes);
    }

    private void assertSafeFailure(String currentKeyId, String encodedKeyRing) {
        CredentialKeyConfigurationException exception = assertThrows(
                CredentialKeyConfigurationException.class,
                () -> parser.parse(currentKeyId, encodedKeyRing));
        if (encodedKeyRing != null && !encodedKeyRing.isBlank()) {
            assertFalse(exception.getMessage().contains(encodedKeyRing));
        }
    }

    private static String entry(String keyId, byte[] key) {
        return keyId + "=" + Base64.getEncoder().encodeToString(key);
    }

    private static byte[] keyBytes(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }
}
