package io.crewscope.server.api;

import io.crewscope.application.correlation.CorrelationCursor;
import io.crewscope.application.correlation.CorrelationEventSource;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/** Signed cursor binding a keyset position to Organization, Team and Correlation scopes. */
public final class CorrelationCursorCodec {

    private static final byte VERSION = 1;
    private static final byte[] DOMAIN =
            "crewscope:correlation-cursor:v1".getBytes(StandardCharsets.UTF_8);
    private static final int SIGNATURE_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 384;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]+");

    private final TeamActivityCursorKeyRing keys;

    public CorrelationCursorCodec(TeamActivityCursorKeyRing keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    public String encode(CorrelationCursor cursor) {
        CorrelationCursor value = Objects.requireNonNull(cursor, "cursor");
        byte[] keyId = keys.currentKeyId().getBytes(StandardCharsets.UTF_8);
        ByteBuffer body = ByteBuffer.allocate(1 + 1 + keyId.length + 48 + 12 + 16 + 1)
                .put(VERSION).put((byte) keyId.length).put(keyId);
        putUuid(body, value.organizationId().value());
        putUuid(body, value.teamId().value());
        putUuid(body, value.correlationId());
        body.putLong(value.occurredAt().value().getEpochSecond())
                .putInt(value.occurredAt().value().getNano());
        putUuid(body, value.eventId());
        body.put((byte) value.source().ordinal());
        byte[] signature = sign(body.array(), keys.currentKey());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer.allocate(body.capacity() + signature.length)
                        .put(body.array()).put(signature).array());
    }

    public CorrelationCursor decode(
            String token,
            OrganizationId organizationId,
            TeamId teamId,
            UUID correlationId) {
        byte[] encoded = canonical(token);
        if (encoded.length <= SIGNATURE_BYTES) {
            throw invalid();
        }
        byte[] body = Arrays.copyOf(encoded, encoded.length - SIGNATURE_BYTES);
        byte[] signature = Arrays.copyOfRange(encoded, body.length, encoded.length);
        try {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            if (buffer.get() != VERSION) {
                throw invalid();
            }
            int keyLength = Byte.toUnsignedInt(buffer.get());
            if (keyLength < 1 || keyLength > 32 || buffer.remaining() < keyLength) {
                throw invalid();
            }
            byte[] keyBytes = new byte[keyLength];
            buffer.get(keyBytes);
            String keyId = new String(keyBytes, StandardCharsets.UTF_8);
            byte[] key = keys.key(keyId);
            if (key == null || !MessageDigest.isEqual(signature, sign(body, key))) {
                throw invalid();
            }
            CorrelationCursor cursor = new CorrelationCursor(
                    new OrganizationId(readUuid(buffer)), new TeamId(readUuid(buffer)),
                    readUuid(buffer),
                    UtcTimestamp.from(Instant.ofEpochSecond(buffer.getLong(), buffer.getInt())),
                    readUuid(buffer), source(buffer.get()));
            if (buffer.hasRemaining()
                    || !cursor.organizationId().equals(organizationId)
                    || !cursor.teamId().equals(teamId)
                    || !cursor.correlationId().equals(correlationId)) {
                throw invalid();
            }
            return cursor;
        } catch (ApiRequestException failure) {
            throw failure;
        } catch (BufferUnderflowException | DateTimeException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static CorrelationEventSource source(byte ordinal) {
        int index = Byte.toUnsignedInt(ordinal);
        CorrelationEventSource[] values = CorrelationEventSource.values();
        if (index >= values.length) {
            throw invalid();
        }
        return values[index];
    }

    private static byte[] canonical(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH
                || !TOKEN.matcher(token).matches()) {
            throw invalid();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token)) {
                throw invalid();
            }
            return decoded;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static byte[] sign(byte[] body, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(DOMAIN);
            return mac.doFinal(body);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HmacSHA256 is unavailable", failure);
        }
    }

    private static void putUuid(ByteBuffer buffer, UUID value) {
        buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static ApiRequestException invalid() {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST, "invalid_cursor",
                "Cursor is invalid or belongs to another Correlation query",
                Map.of("parameter", "after"));
    }
}
