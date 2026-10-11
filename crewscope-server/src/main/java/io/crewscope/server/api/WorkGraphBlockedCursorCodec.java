package io.crewscope.server.api;

import io.crewscope.application.workgraph.BlockedItemPosition;
import io.crewscope.domain.shared.id.OrganizationId;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/**
 * Versioned HMAC codec binding a "currently blocked" keyset position to its Team and project
 * filter (M11-A02, ADR-033).
 *
 * <p>A v1 token is signed over the whole scope — organization, Team, the optional blocked-side
 * project filter — plus an issue time. Replaying a token on another Team or filter is rejected
 * as invalid; replaying one older than the configured maximum age is expired. The codec reuses
 * the Team Activity key ring under its own signing domain, so blocked-work-graph tokens and
 * activity or work-item tokens are never interchangeable even under one key.
 */
public final class WorkGraphBlockedCursorCodec {

    private static final byte VERSION = 1;
    private static final byte[] SIGNING_DOMAIN =
            "crewscope:work-graph-blocked-cursor:v1".getBytes(StandardCharsets.UTF_8);
    private static final int SIGNATURE_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 256;
    /** Issued-at timestamps further in the future than this are rejected as invalid. */
    private static final Duration FUTURE_SKEW = Duration.ofMinutes(5);
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

    private final TeamActivityCursorKeyRing keyRing;
    private final Clock clock;
    private final Duration maximumAge;

    public WorkGraphBlockedCursorCodec(
            TeamActivityCursorKeyRing keyRing, Clock clock, Duration maximumAge) {
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumAge = requireDuration(maximumAge, "maximumAge", Duration.ofMinutes(1));
    }

    public String encode(OrganizationId organizationId, TeamId teamId, BlockedItemPosition position) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(position, "position");
        byte[] body = encodeBody(
                organizationId, teamId, Optional.empty(), position, keyRing.currentKeyId(), clock.instant());
        byte[] signature = sign(body, keyRing.currentKey());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer.allocate(body.length + signature.length)
                        .put(body)
                        .put(signature)
                        .array());
    }

    /** Encodes with the blocked-side project filter bound into the signed scope. */
    public String encode(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            BlockedItemPosition position) {
        Objects.requireNonNull(projectId, "projectId");
        byte[] body = encodeBody(
                organizationId, teamId, Optional.of(projectId), position,
                keyRing.currentKeyId(), clock.instant());
        byte[] signature = sign(body, keyRing.currentKey());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer.allocate(body.length + signature.length)
                        .put(body)
                        .put(signature)
                        .array());
    }

    /**
     * Verifies the signature, the issue-time window and the scope; a token minted for another
     * Team or another project filter is invalid, not merely stale.
     */
    public BlockedItemPosition decode(
            String token,
            OrganizationId expectedOrganizationId,
            TeamId expectedTeamId,
            Optional<WorkProjectId> expectedProjectId) {
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
        Decoded decoded = parseVerified(body);
        validateTime(decoded.issuedAt());
        if (!decoded.organizationId().equals(Objects.requireNonNull(
                expectedOrganizationId, "expectedOrganizationId"))
                || !decoded.teamId().equals(Objects.requireNonNull(expectedTeamId, "expectedTeamId"))
                || !decoded.projectId().equals(
                        Objects.requireNonNull(expectedProjectId, "expectedProjectId"))) {
            throw invalidCursor();
        }
        return decoded.position();
    }

    private static byte[] encodeBody(
            OrganizationId organizationId,
            TeamId teamId,
            Optional<WorkProjectId> projectId,
            BlockedItemPosition position,
            String keyId,
            Instant issuedAt) {
        byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);
        boolean hasProject = projectId.isPresent();
        ByteBuffer body = ByteBuffer.allocate(
                1 + 1 + keyIdBytes.length + 2 * 16 + 1 + (hasProject ? 16 : 0)
                        + 12 + 12 + 16);
        body.put(VERSION).put((byte) keyIdBytes.length).put(keyIdBytes);
        putUuid(body, organizationId.value());
        putUuid(body, teamId.value());
        if (hasProject) {
            body.put((byte) 1);
            putUuid(body, projectId.orElseThrow().value());
        } else {
            body.put((byte) 0);
        }
        body.putLong(issuedAt.getEpochSecond()).putInt(issuedAt.getNano());
        Instant blockedSince = position.blockedSince().value();
        body.putLong(blockedSince.getEpochSecond()).putInt(blockedSince.getNano());
        putUuid(body, position.workItemId().value());
        return body.array();
    }

    private record Decoded(
            OrganizationId organizationId,
            TeamId teamId,
            Optional<WorkProjectId> projectId,
            BlockedItemPosition position,
            Instant issuedAt) {}

    private Decoded parseVerified(byte[] body) {
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
            Optional<WorkProjectId> projectId = Optional.empty();
            if (buffer.get() != 0) {
                projectId = Optional.of(new WorkProjectId(readUuid(buffer)));
            }
            Instant issuedAt = Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
            Instant blockedSince = Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
            WorkItemId workItemId = new WorkItemId(readUuid(buffer));
            if (buffer.hasRemaining()) {
                throw invalidCursor();
            }
            return new Decoded(
                    organizationId,
                    teamId,
                    projectId,
                    new BlockedItemPosition(UtcTimestamp.from(blockedSince), workItemId),
                    issuedAt);
        } catch (ApiRequestException failure) {
            throw failure;
        } catch (BufferUnderflowException | DateTimeException | IllegalArgumentException failure) {
            throw invalidCursor();
        }
    }

    private void validateTime(Instant issuedAt) {
        Instant now = clock.instant();
        if (issuedAt.isAfter(now.plus(FUTURE_SKEW))) {
            throw invalidCursor();
        }
        if (issuedAt.plus(maximumAge).isBefore(now)) {
            throw invalidCursor();
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
                    "WorkGraphBlockedCursorCodec " + name + " must be at least " + minimum);
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
                "Cursor is invalid or belongs to another blocked-work graph stream",
                Map.of("parameter", "after"));
    }
}
