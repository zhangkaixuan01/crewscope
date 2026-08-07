package io.crewscope.application.credential;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Closeable plaintext holder whose internal buffer is cleared after the action finishes. */
public final class CredentialSecret implements AutoCloseable {

    private static final int MAX_SECRET_SIZE = 1024 * 1024;

    private byte[] value;

    private CredentialSecret(byte[] value) {
        byte[] source = Objects.requireNonNull(value, "value");
        if (source.length < 1 || source.length > MAX_SECRET_SIZE) {
            throw new IllegalArgumentException("Credential secret size must be between 1 byte and 1 MiB");
        }
        this.value = source.clone();
    }

    public static CredentialSecret of(byte[] value) {
        return new CredentialSecret(value);
    }

    public static CredentialSecret utf8(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Credential secret must not be empty");
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        try {
            return new CredentialSecret(encoded);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    /** Returns a short-lived defensive copy that the caller must clear after use. */
    public synchronized byte[] copyBytes() {
        requireOpen();
        return value.clone();
    }

    public synchronized boolean isClosed() {
        return value == null;
    }

    @Override
    public synchronized void close() {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
            value = null;
        }
    }

    @Override
    public String toString() {
        return "CredentialSecret[REDACTED]";
    }

    private void requireOpen() {
        if (value == null) {
            throw new IllegalStateException("Credential secret is closed");
        }
    }
}
