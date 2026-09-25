package io.crewscope.server.api;

import io.crewscope.application.principal.PrincipalDirectoryCursor;
import io.crewscope.application.principal.PrincipalDirectoryCursorExpiredException;
import io.crewscope.application.principal.PrincipalDirectoryFilterFingerprint;
import io.crewscope.application.principal.PrincipalDirectoryPurpose;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/**
 * Versioned HMAC codec binding one directory continuation to its tenant, viewer, purpose and
 * filter scope (M9b-A06).
 *
 * <p>A token is signed over the organization, Team, viewing principal, the query purpose (whose
 * visible candidate sets differ) and the canonical filter fingerprint, plus an issue time — so a
 * continuation only replays on the same screen that produced it, and only while fresh; an older
 * one is reported as expired so the client restarts the directory from its first page. The sort
 * key is the page's own ordering key, which may carry up to 200 characters of display name, so
 * this codec accepts a longer token than the section cursors do.
 */
public final class PrincipalDirectoryCursorCodec {

    private static final byte VERSION = 1;
    private static final byte[] SIGNING_DOMAIN =
            "crewscope:principal-directory-cursor:v1".getBytes(StandardCharsets.UTF_8);
    private static final int SIGNATURE_BYTES = 32;
    private static final int FINGERPRINT_BYTES = 32;
    /** display_name is VARCHAR(200); four UTF-8 bytes per character bound the sort key payload. */
    private static final int MAX_SORT_KEY_BYTES = 800;
    private static final int MAX_TOKEN_LENGTH = 2048;
    /** Issued-at timestamps further in the future than this are rejected as invalid. */
    private static final Duration FUTURE_SKEW = Duration.ofSeconds(30);
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

    private final TeamActivityCursorKeyRing keyRing;
    private final Clock clock;
    private final Duration maximumAge;

    public PrincipalDirectoryCursorCodec(
            TeamActivityCursorKeyRing keyRing, Clock clock, Duration maximumAge) {
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumAge = requireDuration(maximumAge, "maximumAge", Duration.ofMinutes(1));
    }

    /**
     * What a continuation must replay on: the tenant, the viewing member (who alone determines
     * which Agent profiles are visible), the purpose whose candidate set differs between
     * ASSIGNMENT and AUDIT, and the filter fingerprint of the screen that produced the position.
     */
    public record PrincipalDirectoryCursorScope(
            OrganizationId organizationId,
            TeamId teamId,
            PrincipalId viewerPrincipalId,
            PrincipalDirectoryPurpose purpose,
            PrincipalDirectoryFilterFingerprint filterFingerprint) {

        public PrincipalDirectoryCursorScope {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(teamId, "teamId");
            Objects.requireNonNull(viewerPrincipalId, "viewerPrincipalId");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(filterFingerprint, "filterFingerprint");
        }
    }

    /** A signed directory position: the scope it belongs to and the keyset tail to continue from. */
    private record SignedPosition(
            PrincipalDirectoryCursorScope scope,
            io.crewscope.application.principal.PrincipalDirectoryCursor position) {

        SignedPosition {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(position, "position");
        }
    }

    public String encode(
            PrincipalDirectoryCursorScope scope,
            io.crewscope.application.principal.PrincipalDirectoryCursor position) {
        SignedPosition cursor = new SignedPosition(
                Objects.requireNonNull(scope, "scope"), Objects.requireNonNull(position, "position"));
        byte[] body = encodeBody(cursor, keyRing.currentKeyId(), clock.instant());
        byte[] signature = sign(body, keyRing.currentKey());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer.allocate(body.length + signature.length)
                        .put(body)
                        .put(signature)
                        .array());
    }

    public io.crewscope.application.principal.PrincipalDirectoryCursor decode(
            String token, PrincipalDirectoryCursorScope expectedScope) {
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
        SignedPosition cursor = parseVerified(body);
        validateTime(parseIssuedAt(body));
        if (!cursor.scope().equals(Objects.requireNonNull(expectedScope, "expectedScope"))) {
            throw invalidCursor();
        }
        return cursor.position();
    }

    private static byte[] encodeBody(
            SignedPosition cursor, String keyId, Instant issuedAt) {
        byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);
        byte[] sortKeyBytes = cursor.position().sortKey().getBytes(StandardCharsets.UTF_8);
        byte[] fingerprint =
                HexFormat.of().parseHex(cursor.scope().filterFingerprint().value());
        ByteBuffer body = ByteBuffer.allocate(
                1 + 1 + keyIdBytes.length + 3 * 16 + 1 + FINGERPRINT_BYTES + 12
                        + 2 + sortKeyBytes.length + 16);
        body.put(VERSION).put((byte) keyIdBytes.length).put(keyIdBytes);
        putUuid(body, cursor.scope().organizationId().value());
        putUuid(body, cursor.scope().teamId().value());
        putUuid(body, cursor.scope().viewerPrincipalId().value());
        body.put((byte) cursor.scope().purpose().ordinal());
        body.put(fingerprint);
        body.putLong(issuedAt.getEpochSecond()).putInt(issuedAt.getNano());
        body.putShort((short) sortKeyBytes.length).put(sortKeyBytes);
        putUuid(body, cursor.position().principalId().value());
        return body.array();
    }

    private SignedPosition parseVerified(byte[] body) {
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
            PrincipalDirectoryPurpose purpose =
                    PrincipalDirectoryPurpose.values()[buffer.get()];
            byte[] fingerprint = new byte[FINGERPRINT_BYTES];
            buffer.get(fingerprint);
            buffer.getLong();
            buffer.getInt();
            int sortKeyLength = Short.toUnsignedInt(buffer.getShort());
            String sortKey = readText(buffer, sortKeyLength, MAX_SORT_KEY_BYTES);
            java.util.UUID sortId = readUuid(buffer);
            if (buffer.hasRemaining()) {
                throw invalidCursor();
            }
            PrincipalDirectoryCursorScope scope = new PrincipalDirectoryCursorScope(
                    organizationId,
                    teamId,
                    viewerPrincipalId,
                    purpose,
                    new PrincipalDirectoryFilterFingerprint(HexFormat.of().formatHex(fingerprint)));
            return new SignedPosition(
                    scope,
                    new io.crewscope.application.principal.PrincipalDirectoryCursor(
                            sortKey, new PrincipalId(sortId)));
        } catch (ApiRequestException failure) {
            throw failure;
        } catch (BufferUnderflowException
                | IndexOutOfBoundsException
                | IllegalArgumentException failure) {
            throw invalidCursor();
        }
    }

    /** Reads back only the issue time; the signature has already been verified by the caller. */
    private static Instant parseIssuedAt(byte[] body) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            buffer.get();
            buffer.position(buffer.position() + 1 + Byte.toUnsignedInt(buffer.get()));
            buffer.position(buffer.position() + 3 * 16 + 1 + FINGERPRINT_BYTES);
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
            throw new PrincipalDirectoryCursorExpiredException();
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
                    "PrincipalDirectoryCursorCodec " + name + " must be at least " + minimum);
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

    private static void putUuid(ByteBuffer buffer, java.util.UUID value) {
        buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    private static java.util.UUID readUuid(ByteBuffer buffer) {
        return new java.util.UUID(buffer.getLong(), buffer.getLong());
    }

    private static ApiRequestException invalidCursor() {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_cursor",
                "Cursor is invalid or belongs to another principal directory query",
                Map.of("parameter", "after"));
    }
}
