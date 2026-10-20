package io.crewscope.server.api;

import io.crewscope.application.organization.OrganizationMemberDirectoryCursor;
import io.crewscope.application.organization.OrganizationMemberDirectoryCursorExpiredException;
import io.crewscope.application.organization.OrganizationMemberDirectoryFilterFingerprint;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * Versioned HMAC codec binding one organization member directory continuation to its tenant,
 * viewer and filter scope (M13b-A01).
 *
 * <p>A token is signed over the organization, the viewing principal and the canonical filter
 * fingerprint of the screen that produced the position, plus an issue time — so a continuation
 * only replays on the same governance screen, and only while fresh; an older one is reported as
 * expired so the client restarts the directory from its first page. The sort key is the page's
 * own ordering key, which may carry up to 200 characters of display name, so this codec accepts a
 * longer token than the section cursors do.
 */
public final class OrganizationMemberCursorCodec
        extends CursorCodec<OrganizationMemberCursorCodec.OrganizationMemberCursor> {

    private static final byte VERSION = 1;
    private static final byte[] SIGNING_DOMAIN =
            "crewscope:organization-member-cursor:v1".getBytes(StandardCharsets.UTF_8);
    private static final int FINGERPRINT_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 2048;
    /** display_name is VARCHAR(200); four UTF-8 bytes per character bound the sort key payload. */
    private static final int MAX_SORT_KEY_BYTES = 800;
    /** Issued-at timestamps further in the future than this are rejected as invalid. */
    private static final Duration FUTURE_SKEW = Duration.ofSeconds(30);

    public OrganizationMemberCursorCodec(
            TeamActivityCursorKeyRing keyRing, Clock clock, Duration maximumAge) {
        super(keyRing, clock, maximumAge, MAX_TOKEN_LENGTH);
    }

    /**
     * What a continuation must replay on: the tenant, the viewing principal (whose grants gate the
     * read) and the filter fingerprint of the screen that produced the position.
     */
    public record OrganizationMemberCursorScope(
            OrganizationId organizationId,
            PrincipalId viewerPrincipalId,
            OrganizationMemberDirectoryFilterFingerprint filterFingerprint) {

        public OrganizationMemberCursorScope {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(viewerPrincipalId, "viewerPrincipalId");
            Objects.requireNonNull(filterFingerprint, "filterFingerprint");
        }
    }

    /** A signed directory position: the scope it belongs to and the keyset tail to continue from. */
    public record OrganizationMemberCursor(
            OrganizationMemberCursorScope scope,
            OrganizationMemberDirectoryCursor position) {

        public OrganizationMemberCursor {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(position, "position");
        }
    }

    public OrganizationMemberDirectoryCursor decode(
            String token, OrganizationMemberCursorScope expectedScope) {
        OrganizationMemberCursor cursor = decodeVerified(token);
        if (!cursor.scope().equals(Objects.requireNonNull(expectedScope, "expectedScope"))) {
            throw invalidCursor();
        }
        return cursor.position();
    }

    @Override
    protected byte version() {
        return VERSION;
    }

    @Override
    protected byte[] signingDomain() {
        return SIGNING_DOMAIN;
    }

    @Override
    protected byte[] encodeBody(
            OrganizationMemberCursor cursor, String keyId, Instant issuedAt) {
        byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);
        byte[] sortKeyBytes = cursor.position().sortKey().getBytes(StandardCharsets.UTF_8);
        byte[] fingerprint =
                HexFormat.of().parseHex(cursor.scope().filterFingerprint().value());
        ByteBuffer body = ByteBuffer.allocate(
                1 + 1 + keyIdBytes.length + 2 * 16 + FINGERPRINT_BYTES + 12
                        + 2 + sortKeyBytes.length + 16);
        body.put(VERSION).put((byte) keyIdBytes.length).put(keyIdBytes);
        putUuid(body, cursor.scope().organizationId().value());
        putUuid(body, cursor.scope().viewerPrincipalId().value());
        body.put(fingerprint);
        putInstant(body, issuedAt);
        body.putShort((short) sortKeyBytes.length).put(sortKeyBytes);
        putUuid(body, cursor.position().principalId().value());
        return body.array();
    }

    @Override
    protected OrganizationMemberCursor parseVerified(byte[] body) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            if (buffer.get() != VERSION) {
                throw invalidCursor();
            }
            // The key id was already resolved and checked while verifying the signature.
            readText(buffer, Byte.toUnsignedInt(buffer.get()), 32);
            OrganizationId organizationId = new OrganizationId(readUuid(buffer));
            PrincipalId viewerPrincipalId = new PrincipalId(readUuid(buffer));
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
            return new OrganizationMemberCursor(
                    new OrganizationMemberCursorScope(
                            organizationId,
                            viewerPrincipalId,
                            new OrganizationMemberDirectoryFilterFingerprint(
                                    HexFormat.of().formatHex(fingerprint))),
                    new OrganizationMemberDirectoryCursor(sortKey, new PrincipalId(sortId)));
        } catch (ApiRequestException failure) {
            throw failure;
        } catch (BufferUnderflowException | IllegalArgumentException failure) {
            throw invalidCursor();
        }
    }

    @Override
    protected void validateTime(OrganizationMemberCursor cursor, byte[] body) {
        Instant issuedAt = parseIssuedAt(body);
        Instant now = clock().instant();
        if (issuedAt.isAfter(now.plus(FUTURE_SKEW))) {
            throw invalidCursor();
        }
        if (issuedAt.plus(maximumAge()).isBefore(now)) {
            throw new OrganizationMemberDirectoryCursorExpiredException();
        }
    }

    /** Reads back only the issue time; the signature has already been verified by the caller. */
    private Instant parseIssuedAt(byte[] body) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            buffer.get();
            buffer.position(buffer.position() + 1 + Byte.toUnsignedInt(buffer.get()));
            buffer.position(buffer.position() + 2 * 16 + FINGERPRINT_BYTES);
            return Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
        } catch (BufferUnderflowException | IllegalArgumentException failure) {
            throw invalidCursor();
        }
    }

    @Override
    protected ApiRequestException invalidCursor() {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_cursor",
                "Cursor is invalid or belongs to another organization member directory query",
                Map.of("parameter", "after"));
    }
}
