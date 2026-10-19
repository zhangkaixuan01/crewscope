package io.crewscope.server.api;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared wire framing for the versioned HMAC keyset cursors (M13-C05): canonical base64url
 * tokens, a length-prefixed key-id header, a detached SHA-256 HMAC signature and the
 * fixed-width field primitives each cursor payload is written with.
 *
 * <p>A subclass declares only its version byte, signing domain, payload field order,
 * expected-scope check and time policy. The signing domain keeps cursors of different
 * surfaces from ever decoding into one another, even though they share a key ring.
 */
abstract class CursorCodec<T> {

    private static final int SIGNATURE_BYTES = 32;
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

    private final TeamActivityCursorKeyRing keyRing;
    private final Clock clock;
    private final Duration maximumAge;
    private final int maximumTokenLength;

    CursorCodec(
            TeamActivityCursorKeyRing keyRing,
            Clock clock,
            Duration maximumAge,
            int maximumTokenLength) {
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumAge = requireMaximumAge(maximumAge);
        this.maximumTokenLength = maximumTokenLength;
    }

    public final String encode(T cursor) {
        T source = Objects.requireNonNull(cursor, "cursor");
        byte[] body = encodeBody(source, keyRing.currentKeyId(), clock.instant());
        byte[] signature = sign(body, keyRing.currentKey());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer.allocate(body.length + signature.length)
                        .put(body)
                        .put(signature)
                        .array());
    }

    /**
     * Verifies the token's framing, key id and signature, then parses and time-checks the
     * payload. The expected-scope comparison stays with the subclass because each surface
     * binds a different scope.
     */
    final T decodeVerified(String token) {
        byte[] encoded = decodeCanonical(token);
        if (encoded.length <= SIGNATURE_BYTES) {
            throw invalidCursor();
        }
        byte[] body = Arrays.copyOf(encoded, encoded.length - SIGNATURE_BYTES);
        byte[] signature = Arrays.copyOfRange(encoded, body.length, encoded.length);
        byte[] key = keyRing.key(readKeyId(body));
        if (key == null || !MessageDigest.isEqual(signature, sign(body, key))) {
            throw invalidCursor();
        }
        T parsed;
        try {
            parsed = parseVerified(body);
        } catch (ApiRequestException failure) {
            throw failure;
        } catch (BufferUnderflowException | DateTimeException | IllegalArgumentException failure) {
            throw invalidCursor();
        }
        validateTime(parsed, body);
        return parsed;
    }

    protected abstract byte version();

    /** The domain prefix fed to the HMAC before the body; {@code null} for no prefix. */
    protected abstract byte[] signingDomain();

    /** Default lower bound shared by the short-lived list cursors; the message names the codec. */
    protected Duration requireMaximumAge(Duration maximumAge) {
        if (maximumAge == null || maximumAge.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException(
                    getClass().getSimpleName() + " maximumAge must be at least PT1M");
        }
        return maximumAge;
    }

    protected abstract byte[] encodeBody(T cursor, String keyId, Instant issuedAt);

    protected abstract T parseVerified(byte[] body);

    protected abstract void validateTime(T cursor, byte[] body);

    protected abstract ApiRequestException invalidCursor();

    protected final Clock clock() {
        return clock;
    }

    protected final Duration maximumAge() {
        return maximumAge;
    }

    private byte[] decodeCanonical(String token) {
        if (token == null
                || token.isBlank()
                || token.length() > maximumTokenLength
                || !TOKEN_FORMAT.matcher(token).matches()) {
            throw invalidCursor();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token)) {
                throw invalidCursor();
            }
            return decoded;
        } catch (IllegalArgumentException failure) {
            throw invalidCursor();
        }
    }

    private String readKeyId(byte[] body) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            if (buffer.get() != version()) {
                throw invalidCursor();
            }
            return readText(buffer, Byte.toUnsignedInt(buffer.get()), 32);
        } catch (BufferUnderflowException | IllegalArgumentException failure) {
            throw invalidCursor();
        }
    }

    private byte[] sign(byte[] body, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] domain = signingDomain();
            if (domain != null) {
                mac.update(domain);
            }
            return mac.doFinal(body);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HmacSHA256 is unavailable", failure);
        }
    }

    protected final String readText(ByteBuffer buffer, int length, int maximumLength) {
        if (length < 1 || length > maximumLength || buffer.remaining() < length) {
            throw invalidCursor();
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
            throw invalidCursor();
        }
        return value;
    }

    protected static void putUuid(ByteBuffer buffer, UUID value) {
        buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    protected static UUID readUuid(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    protected static void putInstant(ByteBuffer buffer, Instant value) {
        buffer.putLong(value.getEpochSecond()).putInt(value.getNano());
    }

    protected static Instant readInstant(ByteBuffer buffer) {
        return Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
    }

    protected static void putOptionalInstant(ByteBuffer buffer, Optional<Instant> value) {
        if (value.isPresent()) {
            buffer.put((byte) 1);
            putInstant(buffer, value.orElseThrow());
        } else {
            buffer.put((byte) 0);
        }
    }

    protected static Optional<Instant> readOptionalInstant(ByteBuffer buffer) {
        if (buffer.get() == 0) {
            return Optional.empty();
        }
        return Optional.of(readInstant(buffer));
    }

    protected static void putOptionalInt(ByteBuffer buffer, OptionalInt value) {
        if (value.isPresent()) {
            buffer.put((byte) 1).putInt(value.orElseThrow());
        } else {
            buffer.put((byte) 0);
        }
    }

    protected static OptionalInt readOptionalInt(ByteBuffer buffer) {
        if (buffer.get() == 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(buffer.getInt());
    }
}
