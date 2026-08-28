package io.crewscope.server.security.login;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Versioned, purpose-separated HMAC for Redis resource coordinates. */
final class LoginDefenseResourceHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final String keyId;
    private final byte[] secret;

    LoginDefenseResourceHasher(String keyId, String base64Secret) {
        this.keyId = requireKeyId(keyId);
        try {
            this.secret = Base64.getDecoder().decode(Objects.requireNonNull(base64Secret, "base64Secret"));
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalStateException("login defense HMAC key must be valid Base64");
        }
        if (secret.length < 32) {
            throw new IllegalStateException("login defense HMAC key must contain at least 32 bytes");
        }
    }

    String digest(String purpose, String preimage) {
        String requiredPurpose = requirePurpose(purpose);
        String requiredPreimage = Objects.requireNonNull(preimage, "preimage");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update(("crewscope:login-defense:v1:" + requiredPurpose + "\0")
                    .getBytes(StandardCharsets.UTF_8));
            byte[] digest = mac.doFinal(requiredPreimage.getBytes(StandardCharsets.UTF_8));
            return keyId + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("login defense HMAC is unavailable");
        }
    }

    private static String requireKeyId(String value) {
        String required = Objects.requireNonNull(value, "HMAC key id").strip();
        if (required.isEmpty()
                || required.length() > 32
                || !required.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalStateException("HMAC key id is invalid");
        }
        return required;
    }

    private static String requirePurpose(String value) {
        String required = Objects.requireNonNull(value, "HMAC purpose").strip();
        if (required.isEmpty()
                || required.length() > 64
                || !required.matches("[a-z0-9:_-]+")) {
            throw new IllegalStateException("HMAC purpose is invalid");
        }
        return required;
    }
}
