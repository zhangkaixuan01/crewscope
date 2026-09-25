package io.crewscope.server.api;

import io.crewscope.application.workitem.WorkItemCursor;
import io.crewscope.application.workitem.WorkItemCursorExpiredException;
import io.crewscope.application.workitem.WorkItemCursorScope;
import io.crewscope.application.workitem.WorkItemFilterFingerprint;
import io.crewscope.application.workitem.WorkItemSort;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
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
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/**
 * Versioned HMAC codec binding a WorkItem keyset position to its tenant, ordering and filter scope
 * (M9b-A06).
 *
 * <p>A v2 token is signed over the whole scope — organization, Team, project, the viewing member,
 * the sort and the normalized filter fingerprint — plus an issue time. Replaying a token on another
 * query, member, sort or filter is rejected as invalid; replaying one older than the configured
 * maximum age is reported as expired so the client knows to restart from the first page rather than
 * retry the same continuation. Unversioned v1 tokens predate scope binding entirely and are always
 * invalid: cursors are transient, never persisted by the client, so a deployment-time break is the
 * honest outcome.
 *
 * <p>The wire ordinal is {@link WorkItemSort#ordinal()}; a future re-ordering of the enum turns old
 * tokens into scope mismatches, which fail closed rather than silently continuing another ordering.
 */
public final class WorkItemCursorCodec {

    private static final byte VERSION = 2;
    private static final byte[] SIGNING_DOMAIN =
            "crewscope:work-item-cursor:v2".getBytes(StandardCharsets.UTF_8);
    private static final int SIGNATURE_BYTES = 32;
    private static final int FINGERPRINT_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 512;
    /** Issued-at timestamps further in the future than this are rejected as invalid. */
    private static final Duration FUTURE_SKEW = Duration.ofSeconds(30);
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

    private final TeamActivityCursorKeyRing keyRing;
    private final Clock clock;
    private final Duration maximumAge;

    public WorkItemCursorCodec(
            TeamActivityCursorKeyRing keyRing, Clock clock, Duration maximumAge) {
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumAge = requireDuration(maximumAge, "maximumAge", Duration.ofMinutes(1));
    }

    public String encode(WorkItemCursor cursor) {
        WorkItemCursor source = Objects.requireNonNull(cursor, "cursor");
        byte[] body = encodeBody(source, keyRing.currentKeyId(), clock.instant());
        byte[] signature = sign(body, keyRing.currentKey());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer.allocate(body.length + signature.length)
                        .put(body)
                        .put(signature)
                        .array());
    }

    public WorkItemCursor decode(String token, WorkItemCursorScope expectedScope) {
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
        WorkItemCursor cursor = parseVerified(body);
        validateTime(parseIssuedAt(body));
        if (!cursor.scope().equals(Objects.requireNonNull(expectedScope, "expectedScope"))) {
            throw invalidCursor();
        }
        return cursor;
    }

    private static byte[] encodeBody(WorkItemCursor cursor, String keyId, Instant issuedAt) {
        byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);
        byte[] fingerprint = HexFormat.of().parseHex(cursor.scope().filterFingerprint().value());
        boolean hasTime = cursor.primaryTime().isPresent();
        boolean hasRank = cursor.primaryRank().isPresent();
        ByteBuffer body = ByteBuffer.allocate(
                1 + 1 + keyIdBytes.length + 4 * 16 + 1 + FINGERPRINT_BYTES + 12
                        + 1 + (hasTime ? 12 : 0) + 1 + (hasRank ? 4 : 0) + 16);
        body.put(VERSION).put((byte) keyIdBytes.length).put(keyIdBytes);
        putUuid(body, cursor.scope().organizationId().value());
        putUuid(body, cursor.scope().teamId().value());
        putUuid(body, cursor.scope().projectId().value());
        putUuid(body, cursor.scope().viewerPrincipalId().value());
        body.put((byte) cursor.scope().sort().ordinal()).put(fingerprint);
        body.putLong(issuedAt.getEpochSecond()).putInt(issuedAt.getNano());
        if (hasTime) {
            Instant time = cursor.primaryTime().orElseThrow().value();
            body.put((byte) 1).putLong(time.getEpochSecond()).putInt(time.getNano());
        } else {
            body.put((byte) 0);
        }
        if (hasRank) {
            body.put((byte) 1).putInt(cursor.primaryRank().orElseThrow());
        } else {
            body.put((byte) 0);
        }
        putUuid(body, cursor.id().value());
        return body.array();
    }

    private WorkItemCursor parseVerified(byte[] body) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            if (buffer.get() != VERSION) {
                throw invalidCursor();
            }
            String keyId = readText(buffer, Byte.toUnsignedInt(buffer.get()), 32);
            if (keyRing.key(keyId) == null) {
                throw invalidCursor();
            }
            OrganizationId organizationId = new OrganizationId(readUuid(buffer));
            TeamId teamId = new TeamId(readUuid(buffer));
            WorkProjectId projectId = new WorkProjectId(readUuid(buffer));
            PrincipalId viewerPrincipalId = new PrincipalId(readUuid(buffer));
            int sortOrdinal = Byte.toUnsignedInt(buffer.get());
            WorkItemSort[] sorts = WorkItemSort.values();
            if (sortOrdinal >= sorts.length) {
                throw invalidCursor();
            }
            byte[] fingerprint = new byte[FINGERPRINT_BYTES];
            buffer.get(fingerprint);
            WorkItemCursorScope scope = new WorkItemCursorScope(
                    organizationId,
                    teamId,
                    projectId,
                    viewerPrincipalId,
                    sorts[sortOrdinal],
                    new WorkItemFilterFingerprint(HexFormat.of().formatHex(fingerprint)));
            buffer.getLong();
            buffer.getInt();
            Optional<UtcTimestamp> primaryTime = Optional.empty();
            if (buffer.get() != 0) {
                primaryTime = Optional.of(
                        UtcTimestamp.from(Instant.ofEpochSecond(buffer.getLong(), buffer.getInt())));
            }
            OptionalInt primaryRank = OptionalInt.empty();
            if (buffer.get() != 0) {
                primaryRank = OptionalInt.of(buffer.getInt());
            }
            WorkItemId id = new WorkItemId(readUuid(buffer));
            if (buffer.hasRemaining()) {
                throw invalidCursor();
            }
            return new WorkItemCursor(scope, primaryTime, primaryRank, id);
        } catch (ApiRequestException failure) {
            throw failure;
        } catch (BufferUnderflowException | DateTimeException | IllegalArgumentException failure) {
            throw invalidCursor();
        }
    }

    /** Reads back only the issue time; the signature has already been verified by the caller. */
    private static Instant parseIssuedAt(byte[] body) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            buffer.get();
            buffer.position(buffer.position() + 1 + Byte.toUnsignedInt(buffer.get()));
            buffer.position(buffer.position() + 4 * 16 + 1 + FINGERPRINT_BYTES);
            return Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
        } catch (BufferUnderflowException | IllegalArgumentException failure) {
            throw invalidCursor();
        }
    }

    private void validateTime(Instant issuedAt) {
        Instant now = clock.instant();
        if (issuedAt.isAfter(now.plus(FUTURE_SKEW))) {
            throw invalidCursor();
        }
        if (issuedAt.plus(maximumAge).isBefore(now)) {
            throw new WorkItemCursorExpiredException();
        }
    }

    private static byte[] decodeCanonical(String token) {
        if (token == null
                || token.isBlank()
                || token.length() > MAX_TOKEN_LENGTH
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

    private static String readKeyId(byte[] body) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            if (buffer.get() != VERSION) {
                throw invalidCursor();
            }
            return readText(buffer, Byte.toUnsignedInt(buffer.get()), 32);
        } catch (BufferUnderflowException | IllegalArgumentException failure) {
            throw invalidCursor();
        }
    }

    private static String readText(ByteBuffer buffer, int length, int maximumLength) {
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

    private static Duration requireDuration(Duration value, String name, Duration minimum) {
        if (value == null || value.compareTo(minimum) < 0) {
            throw new IllegalArgumentException(
                    "WorkItemCursorCodec " + name + " must be at least " + minimum);
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

    private static ApiRequestException invalidCursor() {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_cursor",
                "Cursor is invalid or belongs to another WorkItem query",
                Map.of("parameter", "after"));
    }
}
