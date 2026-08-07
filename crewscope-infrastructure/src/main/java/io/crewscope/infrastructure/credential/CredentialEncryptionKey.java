package io.crewscope.infrastructure.credential;

import java.util.Arrays;
import java.util.Objects;

/** In-memory AES-256 key material identified by the non-secret key ID stored with each envelope. */
public final class CredentialEncryptionKey implements AutoCloseable {

    private static final int AES_256_KEY_SIZE = 32;
    private static final String KEY_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}";

    private final String keyId;
    private byte[] keyBytes;

    public CredentialEncryptionKey(String keyId, byte[] keyBytes) {
        if (keyId == null || !keyId.strip().matches(KEY_ID_PATTERN)) {
            throw new IllegalArgumentException("keyId has an invalid format");
        }
        byte[] source = Objects.requireNonNull(keyBytes, "keyBytes");
        if (source.length != AES_256_KEY_SIZE) {
            throw new IllegalArgumentException("AES-256 key material must contain exactly 32 bytes");
        }
        this.keyId = keyId.strip();
        this.keyBytes = source.clone();
    }

    public String keyId() {
        return keyId;
    }

    synchronized byte[] copyKeyBytes() {
        if (keyBytes == null) {
            throw new IllegalStateException("Credential encryption key is closed");
        }
        return keyBytes.clone();
    }

    @Override
    public synchronized void close() {
        if (keyBytes != null) {
            Arrays.fill(keyBytes, (byte) 0);
            keyBytes = null;
        }
    }

    @Override
    public String toString() {
        return "CredentialEncryptionKey[keyId=" + keyId + ", key=REDACTED]";
    }
}
