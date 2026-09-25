package io.crewscope.server.api;

import io.crewscope.application.workdesk.WorkDeskCursorExpiredException;
import io.crewscope.application.workdesk.WorkDeskSectionPosition;
import io.crewscope.application.workitem.WorkItemFilterFingerprint;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
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
 * Versioned HMAC codec binding one WorkDesk section's keyset position to its tenant, section and
 * filter scope (M9b-A06).
 *
 * <p>A token is signed over the organization, Team, viewing principal, the section key and the
 * canonical filter fingerprint, plus an issue time — so a continuation only replays on the same
 * member, section and filter that produced it, and only while fresh; an older one is reported as
 * expired so the client restarts the section from its first screen. The Inbox-shaped position
 * carries the same four keys the Inbox's own cursor does, so both surfaces walk one ordering.
 */
public final class WorkDeskSectionCursorCodec {

    private static final byte VERSION = 1;
    private static final byte[] SIGNING_DOMAIN =
            "crewscope:work-desk-section-cursor:v1".getBytes(StandardCharsets.UTF_8);
    private static final int SIGNATURE_BYTES = 32;
    private static final int FINGERPRINT_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final int MAX_SECTION_KEY_BYTES = 24;
    /** Issued-at timestamps further in the future than this are rejected as invalid. */
    private static final Duration FUTURE_SKEW = Duration.ofSeconds(30);
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

    private final TeamActivityCursorKeyRing keyRing;
    private final Clock clock;
    private final Duration maximumAge;

    public WorkDeskSectionCursorCodec(
            TeamActivityCursorKeyRing keyRing, Clock clock, Duration maximumAge) {
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumAge = requireDuration(maximumAge, "maximumAge", Duration.ofMinutes(1));
    }

    /**
     * What a continuation must replay on: the tenant, the viewing principal (who alone determines
     * the Team membership the section reads), the one section being continued, and the filter
     * fingerprint of the screen that produced the position.
     */
    public record WorkDeskSectionCursorScope(
            OrganizationId organizationId,
            TeamId teamId,
            PrincipalId viewerPrincipalId,
            String sectionKey,
            WorkItemFilterFingerprint filterFingerprint) {

        public WorkDeskSectionCursorScope {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(teamId, "teamId");
            Objects.requireNonNull(viewerPrincipalId, "viewerPrincipalId");
            Objects.requireNonNull(filterFingerprint, "filterFingerprint");
            if (sectionKey == null || sectionKey.isBlank()
                    || sectionKey.getBytes(StandardCharsets.UTF_8).length > MAX_SECTION_KEY_BYTES) {
                throw new IllegalArgumentException(
                        "sectionKey must be a bounded non-blank WorkDesk section key");
            }
        }
    }

    /** A signed section position: the scope it belongs to and the keyset tail to continue from. */
    public record WorkDeskSectionCursor(
            WorkDeskSectionCursorScope scope, WorkDeskSectionPosition position) {

        public WorkDeskSectionCursor {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(position, "position");
        }
    }

    public String encode(WorkDeskSectionCursor cursor) {
        WorkDeskSectionCursor source = Objects.requireNonNull(cursor, "cursor");
        byte[] body = encodeBody(source, keyRing.currentKeyId(), clock.instant());
        byte[] signature = sign(body, keyRing.currentKey());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer.allocate(body.length + signature.length)
                        .put(body)
                        .put(signature)
                        .array());
    }

    public WorkDeskSectionCursor decode(String token, WorkDeskSectionCursorScope expectedScope) {
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
        WorkDeskSectionCursor cursor = parseVerified(body);
        validateTime(parseIssuedAt(body));
        if (!cursor.scope().equals(Objects.requireNonNull(expectedScope, "expectedScope"))) {
            throw invalidCursor();
        }
        return cursor;
    }

    private static byte[] encodeBody(
            WorkDeskSectionCursor cursor, String keyId, Instant issuedAt) {
        byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);
        byte[] sectionKeyBytes = cursor.scope().sectionKey().getBytes(StandardCharsets.UTF_8);
        byte[] fingerprint = HexFormat.of().parseHex(cursor.scope().filterFingerprint().value());
        WorkDeskSectionPosition position = cursor.position();
        boolean hasRank = position.primaryRank().isPresent();
        boolean hasDeadline = position.deadline().isPresent();
        ByteBuffer body = ByteBuffer.allocate(
                1 + 1 + keyIdBytes.length + 3 * 16 + 1 + sectionKeyBytes.length
                        + FINGERPRINT_BYTES + 12
                        + 1 + (hasRank ? 4 : 0) + 1 + (hasDeadline ? 12 : 0) + 12 + 16);
        body.put(VERSION).put((byte) keyIdBytes.length).put(keyIdBytes);
        putUuid(body, cursor.scope().organizationId().value());
        putUuid(body, cursor.scope().teamId().value());
        putUuid(body, cursor.scope().viewerPrincipalId().value());
        body.put((byte) sectionKeyBytes.length).put(sectionKeyBytes).put(fingerprint);
        body.putLong(issuedAt.getEpochSecond()).putInt(issuedAt.getNano());
        if (hasRank) {
            body.put((byte) 1).putInt(position.primaryRank().orElseThrow());
        } else {
            body.put((byte) 0);
        }
        if (hasDeadline) {
            Instant deadline = position.deadline().orElseThrow().value();
            body.put((byte) 1).putLong(deadline.getEpochSecond()).putInt(deadline.getNano());
        } else {
            body.put((byte) 0);
        }
        Instant sortTime = position.sortTime().value();
        body.putLong(sortTime.getEpochSecond()).putInt(sortTime.getNano());
        putUuid(body, position.sortId());
        return body.array();
    }

    private WorkDeskSectionCursor parseVerified(byte[] body) {
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
            PrincipalId viewerPrincipalId = new PrincipalId(readUuid(buffer));
            String sectionKey = readText(
                    buffer, Byte.toUnsignedInt(buffer.get()), MAX_SECTION_KEY_BYTES);
            byte[] fingerprint = new byte[FINGERPRINT_BYTES];
            buffer.get(fingerprint);
            buffer.getLong();
            buffer.getInt();
            OptionalInt primaryRank = OptionalInt.empty();
            if (buffer.get() != 0) {
                primaryRank = OptionalInt.of(buffer.getInt());
            }
            Optional<UtcTimestamp> deadline = Optional.empty();
            if (buffer.get() != 0) {
                deadline = Optional.of(
                        UtcTimestamp.from(Instant.ofEpochSecond(buffer.getLong(), buffer.getInt())));
            }
            UtcTimestamp sortTime =
                    UtcTimestamp.from(Instant.ofEpochSecond(buffer.getLong(), buffer.getInt()));
            UUID sortId = readUuid(buffer);
            if (buffer.hasRemaining()) {
                throw invalidCursor();
            }
            WorkDeskSectionCursorScope scope = new WorkDeskSectionCursorScope(
                    organizationId, teamId, viewerPrincipalId, sectionKey,
                    new WorkItemFilterFingerprint(HexFormat.of().formatHex(fingerprint)));
            return new WorkDeskSectionCursor(
                    scope,
                    new WorkDeskSectionPosition(sectionKey, primaryRank, deadline, sortTime, sortId));
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
            buffer.position(buffer.position() + 3 * 16);
            buffer.position(buffer.position() + 1 + Byte.toUnsignedInt(buffer.get()));
            buffer.position(buffer.position() + FINGERPRINT_BYTES);
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
            throw new WorkDeskCursorExpiredException();
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
                    "WorkDeskSectionCursorCodec " + name + " must be at least " + minimum);
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
                "Cursor is invalid or belongs to another WorkDesk section query",
                Map.of("parameter", "after"));
    }
}
