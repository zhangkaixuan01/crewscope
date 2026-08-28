package io.crewscope.application.identity;

import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.Username;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

/** Ephemeral canonical login identifier whose observable string form is always redacted. */
public final class LoginIdentifierResource {

    private static final int MAX_INPUT_CODE_POINTS = 1_024;
    private static final int MAX_INPUT_UTF8_BYTES = 4_096;

    private final String canonicalValue;

    private LoginIdentifierResource(String canonicalValue) {
        this.canonicalValue = canonicalValue;
    }

    /** Derives the same key as Account lookup without requiring the Account to exist. */
    public static LoginIdentifierResource fromSubmitted(String submitted) {
        if (submitted == null) {
            return new LoginIdentifierResource("__invalid_null__");
        }
        if (submitted.codePointCount(0, submitted.length()) > MAX_INPUT_CODE_POINTS
                || submitted.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_UTF8_BYTES) {
            // Preserve per-input throttling without retaining an attacker-controlled oversized
            // preimage. The infrastructure HMAC still protects this bounded digest at rest.
            return new LoginIdentifierResource("invalid-oversized:"
                    + sha256(submitted.getBytes(StandardCharsets.UTF_8)));
        }
        String value;
        try {
            value = Normalizer.normalize(submitted, Normalizer.Form.NFKC)
                    .strip()
                    .toLowerCase(Locale.ROOT);
        } catch (RuntimeException invalidUnicode) {
            return new LoginIdentifierResource("__invalid_unicode__");
        }
        if (value.isEmpty() || value.codePoints().anyMatch(LoginIdentifierResource::isUnsafe)) {
            return new LoginIdentifierResource("__invalid_text__");
        }
        try {
            String canonical = value.indexOf('@') >= 0
                    ? new NormalizedEmail(value).value()
                    : new Username(value).normalizedValue();
            return new LoginIdentifierResource(canonical);
        } catch (DomainValidationException invalidIdentifier) {
            // Invalid but bounded identifiers retain a deterministic resource bucket.
            return new LoginIdentifierResource("invalid:" + value);
        }
    }

    /** Trusted HMAC adapters must never log or persist this preimage. */
    public String canonicalValue() {
        return canonicalValue;
    }

    @Override
    public String toString() {
        return "LoginIdentifierResource[REDACTED]";
    }

    private static boolean isUnsafe(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.SURROGATE
                || type == Character.PRIVATE_USE
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
