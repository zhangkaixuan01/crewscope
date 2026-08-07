package io.crewscope.infrastructure.credential;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Current and historical AES keys retained only for active envelope reads and rewrap. */
public final class CredentialKeyRing implements AutoCloseable {

    private final String currentKeyId;
    private final Map<String, CredentialEncryptionKey> keys;

    private CredentialKeyRing(
            String currentKeyId, Map<String, CredentialEncryptionKey> keys) {
        this.currentKeyId = Objects.requireNonNull(currentKeyId, "currentKeyId");
        this.keys = Map.copyOf(Objects.requireNonNull(keys, "keys"));
        if (this.keys.isEmpty() || !this.keys.containsKey(currentKeyId)) {
            throw new CredentialKeyConfigurationException(
                    "Credential key ring must contain its current key ID");
        }
    }

    public static CredentialKeyRing of(
            String currentKeyId, Map<String, byte[]> keyMaterial) {
        Objects.requireNonNull(keyMaterial, "keyMaterial");
        LinkedHashMap<String, CredentialEncryptionKey> keys = new LinkedHashMap<>();
        try {
            keyMaterial.forEach((keyId, bytes) -> keys.put(
                    keyId, new CredentialEncryptionKey(keyId, bytes)));
            return new CredentialKeyRing(currentKeyId, keys);
        } catch (RuntimeException exception) {
            keys.values().forEach(CredentialEncryptionKey::close);
            throw exception;
        }
    }

    static CredentialKeyRing single(CredentialEncryptionKey key) {
        CredentialEncryptionKey required = Objects.requireNonNull(key, "key");
        return new CredentialKeyRing(required.keyId(), Map.of(required.keyId(), required));
    }

    public String currentKeyId() {
        return currentKeyId;
    }

    public Set<String> keyIds() {
        return keys.keySet();
    }

    CredentialEncryptionKey currentKey() {
        return keys.get(currentKeyId);
    }

    Optional<CredentialEncryptionKey> find(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }

    @Override
    public void close() {
        keys.values().forEach(CredentialEncryptionKey::close);
    }

    @Override
    public String toString() {
        return "CredentialKeyRing[currentKeyId=" + currentKeyId
                + ", keyCount=" + keys.size() + ", keyMaterial=REDACTED]";
    }
}
