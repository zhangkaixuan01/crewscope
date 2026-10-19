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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
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
public final class WorkDeskSectionCursorCodec extends CursorCodec<WorkDeskSectionCursorCodec.WorkDeskSectionCursor> {

    private static final byte VERSION = 1;
    private static final byte[] SIGNING_DOMAIN =
            "crewscope:work-desk-section-cursor:v1".getBytes(StandardCharsets.UTF_8);
    private static final int FINGERPRINT_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final int MAX_SECTION_KEY_BYTES = 24;
    /** Issued-at timestamps further in the future than this are rejected as invalid. */
    private static final Duration FUTURE_SKEW = Duration.ofSeconds(30);

    public WorkDeskSectionCursorCodec(
            TeamActivityCursorKeyRing keyRing, Clock clock, Duration maximumAge) {
        super(keyRing, clock, maximumAge, MAX_TOKEN_LENGTH);
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

    public WorkDeskSectionCursor decode(String token, WorkDeskSectionCursorScope expectedScope) {
        WorkDeskSectionCursor cursor = decodeVerified(token);
        if (!cursor.scope().equals(Objects.requireNonNull(expectedScope, "expectedScope"))) {
            throw invalidCursor();
        }
        return cursor;
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
        putInstant(body, issuedAt);
        putOptionalInt(body, position.primaryRank());
        putOptionalInstant(body, position.deadline().map(UtcTimestamp::value));
        putInstant(body, position.sortTime().value());
        putUuid(body, position.sortId());
        return body.array();
    }

    @Override
    protected WorkDeskSectionCursor parseVerified(byte[] body) {
        ByteBuffer buffer = ByteBuffer.wrap(body);
        if (buffer.get() != VERSION) {
            throw invalidCursor();
        }
        // The key id was already resolved and checked while verifying the signature.
        readText(buffer, Byte.toUnsignedInt(buffer.get()), 32);
        OrganizationId organizationId = new OrganizationId(readUuid(buffer));
        TeamId teamId = new TeamId(readUuid(buffer));
        PrincipalId viewerPrincipalId = new PrincipalId(readUuid(buffer));
        String sectionKey = readText(
                buffer, Byte.toUnsignedInt(buffer.get()), MAX_SECTION_KEY_BYTES);
        byte[] fingerprint = new byte[FINGERPRINT_BYTES];
        buffer.get(fingerprint);
        buffer.getLong();
        buffer.getInt();
        OptionalInt primaryRank = readOptionalInt(buffer);
        Optional<UtcTimestamp> deadline = readOptionalInstant(buffer).map(UtcTimestamp::from);
        UtcTimestamp sortTime = UtcTimestamp.from(readInstant(buffer));
        WorkDeskSectionCursorScope scope = new WorkDeskSectionCursorScope(
                organizationId, teamId, viewerPrincipalId, sectionKey,
                new WorkItemFilterFingerprint(HexFormat.of().formatHex(fingerprint)));
        WorkDeskSectionCursor cursor = new WorkDeskSectionCursor(
                scope,
                new WorkDeskSectionPosition(sectionKey, primaryRank, deadline, sortTime,
                        readUuid(buffer)));
        if (buffer.hasRemaining()) {
            throw invalidCursor();
        }
        return cursor;
    }

    @Override
    protected void validateTime(WorkDeskSectionCursor cursor, byte[] body) {
        Instant issuedAt = parseIssuedAt(body);
        Instant now = clock().instant();
        if (issuedAt.isAfter(now.plus(FUTURE_SKEW))) {
            throw invalidCursor();
        }
        if (issuedAt.plus(maximumAge()).isBefore(now)) {
            throw new WorkDeskCursorExpiredException();
        }
    }

    /** Reads back only the issue time; the signature has already been verified by the caller. */
    private Instant parseIssuedAt(byte[] body) {
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

    @Override
    protected ApiRequestException invalidCursor() {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_cursor",
                "Cursor is invalid or belongs to another WorkDesk section query",
                Map.of("parameter", "after"));
    }
}
