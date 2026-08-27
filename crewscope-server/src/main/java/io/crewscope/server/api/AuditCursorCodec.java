package io.crewscope.server.api;

import io.crewscope.application.audit.AuditCursor;
import io.crewscope.application.audit.AuditCursorScope;
import io.crewscope.application.audit.AuditFilterFingerprint;
import io.crewscope.application.audit.AuditQueryFilter;
import io.crewscope.domain.audit.AuditEventId;
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
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/** Versioned HMAC codec binding an Audit keyset position to Team and normalized filters. */
public final class AuditCursorCodec {

    private static final byte VERSION = 1;
    private static final byte[] SIGNING_DOMAIN =
            "crewscope:audit-cursor:v1".getBytes(StandardCharsets.UTF_8);
    private static final int SIGNATURE_BYTES = 32;
    private static final int FINGERPRINT_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

    private final TeamActivityCursorKeyRing keyRing;

    public AuditCursorCodec(TeamActivityCursorKeyRing keyRing) {
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
    }

    public String encode(AuditCursor cursor) {
        AuditCursor source = Objects.requireNonNull(cursor, "cursor");
        byte[] body = encodeBody(source, keyRing.currentKeyId());
        byte[] signature = sign(body, keyRing.currentKey());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer.allocate(body.length + signature.length)
                        .put(body)
                        .put(signature)
                        .array());
    }

    public AuditCursor decode(
            String token,
            OrganizationId expectedOrganizationId,
            TeamId expectedTeamId,
            AuditQueryFilter expectedFilter) {
        byte[] encoded = decodeCanonical(token);
        if (encoded.length <= SIGNATURE_BYTES) {
            throw invalid();
        }
        byte[] body = Arrays.copyOf(encoded, encoded.length - SIGNATURE_BYTES);
        byte[] signature = Arrays.copyOfRange(encoded, body.length, encoded.length);
        byte[] key = keyRing.key(readKeyId(body));
        if (key == null || !MessageDigest.isEqual(signature, sign(body, key))) {
            throw invalid();
        }
        AuditCursor cursor = parseVerified(body);
        AuditCursorScope scope = cursor.scope();
        if (!scope.organizationId().equals(
                        Objects.requireNonNull(expectedOrganizationId, "expectedOrganizationId"))
                || !scope.teamId().equals(Objects.requireNonNull(expectedTeamId, "expectedTeamId"))
                || !scope.filterFingerprint().equals(
                        Objects.requireNonNull(expectedFilter, "expectedFilter").fingerprint())) {
            throw invalid();
        }
        return cursor;
    }

    private static byte[] encodeBody(AuditCursor cursor, String keyId) {
        byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);
        byte[] fingerprint = HexFormat.of().parseHex(
                cursor.scope().filterFingerprint().value());
        int size = 1 + 1 + keyIdBytes.length + 32 + FINGERPRINT_BYTES + 12 + 16;
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.put(VERSION).put((byte) keyIdBytes.length).put(keyIdBytes);
        putUuid(buffer, cursor.scope().organizationId().value());
        putUuid(buffer, cursor.scope().teamId().value());
        buffer.put(fingerprint);
        buffer.putLong(cursor.occurredAt().value().getEpochSecond());
        buffer.putInt(cursor.occurredAt().value().getNano());
        putUuid(buffer, cursor.eventId().value());
        return buffer.array();
    }

    private AuditCursor parseVerified(byte[] body) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            if (buffer.get() != VERSION) {
                throw invalid();
            }
            String keyId = readText(buffer, Byte.toUnsignedInt(buffer.get()), 32);
            if (keyRing.key(keyId) == null) {
                throw invalid();
            }
            OrganizationId organizationId = new OrganizationId(readUuid(buffer));
            TeamId teamId = new TeamId(readUuid(buffer));
            byte[] fingerprint = new byte[FINGERPRINT_BYTES];
            buffer.get(fingerprint);
            Instant occurredAt = Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
            AuditEventId eventId = new AuditEventId(readUuid(buffer));
            if (buffer.hasRemaining()) {
                throw invalid();
            }
            return new AuditCursor(
                    new AuditCursorScope(
                            organizationId,
                            teamId,
                            new AuditFilterFingerprint(HexFormat.of().formatHex(fingerprint))),
                    UtcTimestamp.from(occurredAt),
                    eventId);
        } catch (ApiRequestException failure) {
            throw failure;
        } catch (BufferUnderflowException | DateTimeException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static byte[] decodeCanonical(String token) {
        if (token == null
                || token.isBlank()
                || token.length() > MAX_TOKEN_LENGTH
                || !TOKEN_FORMAT.matcher(token).matches()) {
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

    private static String readKeyId(byte[] body) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            if (buffer.get() != VERSION) {
                throw invalid();
            }
            return readText(buffer, Byte.toUnsignedInt(buffer.get()), 32);
        } catch (BufferUnderflowException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static String readText(ByteBuffer buffer, int length, int maximumLength) {
        if (length < 1 || length > maximumLength || buffer.remaining() < length) {
            throw invalid();
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
            throw invalid();
        }
        return value;
    }

    private static byte[] sign(byte[] body, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(SIGNING_DOMAIN);
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
                HttpStatus.BAD_REQUEST,
                "invalid_cursor",
                "Cursor is invalid or belongs to another Audit query",
                Map.of("parameter", "after"));
    }
}
