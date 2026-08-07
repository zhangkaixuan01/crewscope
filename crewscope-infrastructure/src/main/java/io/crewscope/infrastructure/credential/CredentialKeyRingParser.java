package io.crewscope.infrastructure.credential;

import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strict parser for the process-external semicolon-separated key ring configuration. */
public final class CredentialKeyRingParser {

    private static final int MAX_KEYS = 16;

    public CredentialKeyRing parse(String currentKeyId, String encodedKeyRing) {
        if (currentKeyId == null || currentKeyId.isBlank()) {
            throw invalid("Credential current key ID is required");
        }
        if (encodedKeyRing == null || encodedKeyRing.isBlank()) {
            throw invalid("Credential key ring is required");
        }
        String[] entries = encodedKeyRing.split(";", -1);
        if (entries.length < 1 || entries.length > MAX_KEYS) {
            throw invalid("Credential key ring must contain between 1 and 16 keys");
        }
        Map<String, byte[]> decoded = new LinkedHashMap<>();
        try {
            for (String entry : entries) {
                int separator = entry.indexOf('=');
                if (separator < 1 || separator == entry.length() - 1) {
                    throw invalid("Credential key ring entry has an invalid format");
                }
                String keyId = entry.substring(0, separator).strip();
                String encoded = entry.substring(separator + 1).strip();
                byte[] keyBytes;
                try {
                    keyBytes = Base64.getDecoder().decode(encoded);
                } catch (IllegalArgumentException exception) {
                    throw invalid("Credential key ring contains invalid Base64");
                }
                if (keyBytes.length != 32) {
                    Arrays.fill(keyBytes, (byte) 0);
                    throw invalid("Credential key ring contains a non-AES-256 key");
                }
                if (decoded.putIfAbsent(keyId, keyBytes) != null) {
                    Arrays.fill(keyBytes, (byte) 0);
                    throw invalid("Credential key ring contains duplicate key IDs");
                }
            }
            try {
                return CredentialKeyRing.of(currentKeyId.strip(), decoded);
            } catch (RuntimeException exception) {
                throw invalid("Credential key ring or current key ID is invalid");
            }
        } finally {
            decoded.values().forEach(value -> Arrays.fill(value, (byte) 0));
        }
    }

    private static CredentialKeyConfigurationException invalid(String message) {
        return new CredentialKeyConfigurationException(message);
    }
}
