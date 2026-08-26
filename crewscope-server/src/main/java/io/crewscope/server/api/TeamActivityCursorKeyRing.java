package io.crewscope.server.api;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Rotatable server-only HMAC key ring for opaque Team Activity cursors. */
public final class TeamActivityCursorKeyRing {

  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");

  private final String currentKeyId;
  private final Map<String, byte[]> keys;

  public TeamActivityCursorKeyRing(String currentKeyId, Map<String, String> encodedKeys) {
    this.currentKeyId = requireKeyId(currentKeyId);
    Map<String, byte[]> decoded = new LinkedHashMap<>();
    Objects.requireNonNull(encodedKeys, "encodedKeys")
        .forEach(
            (keyId, encoded) -> {
              String normalizedId = requireKeyId(keyId);
              byte[] secret;
              try {
                secret =
                    Base64.getDecoder()
                        .decode(Objects.requireNonNull(encoded, "encoded Team cursor key"));
              } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(
                    "Team Activity cursor keys must use standard Base64", failure);
              }
              if (secret.length < 32) {
                throw new IllegalArgumentException(
                    "Team Activity cursor HMAC keys must contain at least 32 bytes");
              }
              if (decoded.put(normalizedId, secret.clone()) != null) {
                throw new IllegalArgumentException("Team Activity cursor key IDs must be unique");
              }
            });
    if (!decoded.containsKey(this.currentKeyId)) {
      throw new IllegalArgumentException(
          "Team Activity cursor current key ID must exist in the key ring");
    }
    this.keys = Map.copyOf(decoded);
  }

  String currentKeyId() {
    return currentKeyId;
  }

  byte[] currentKey() {
    return keys.get(currentKeyId).clone();
  }

  byte[] key(String keyId) {
    byte[] value = keys.get(keyId);
    return value == null ? null : value.clone();
  }

  private static String requireKeyId(String value) {
    if (value == null || !KEY_ID.matcher(value.strip()).matches()) {
      throw new IllegalArgumentException(
          "Team Activity cursor key ID must be a bounded portable identifier");
    }
    return value.strip();
  }

  @Override
  public String toString() {
    return "TeamActivityCursorKeyRing[currentKeyId=" + currentKeyId + ", keys=[REDACTED]]";
  }
}
