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
public final class WorkItemCursorCodec extends CursorCodec<WorkItemCursor> {

    private static final byte VERSION = 2;
    private static final byte[] SIGNING_DOMAIN =
            "crewscope:work-item-cursor:v2".getBytes(StandardCharsets.UTF_8);
    private static final int FINGERPRINT_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 512;
    /** Issued-at timestamps further in the future than this are rejected as invalid. */
    private static final Duration FUTURE_SKEW = Duration.ofSeconds(30);

    public WorkItemCursorCodec(
            TeamActivityCursorKeyRing keyRing, Clock clock, Duration maximumAge) {
        super(keyRing, clock, maximumAge, MAX_TOKEN_LENGTH);
    }

    public WorkItemCursor decode(String token, WorkItemCursorScope expectedScope) {
        WorkItemCursor cursor = decodeVerified(token);
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
    protected byte[] encodeBody(WorkItemCursor cursor, String keyId, Instant issuedAt) {
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
        putInstant(body, issuedAt);
        putOptionalInstant(body, cursor.primaryTime().map(UtcTimestamp::value));
        putOptionalInt(body, cursor.primaryRank());
        putUuid(body, cursor.id().value());
        return body.array();
    }

    @Override
    protected WorkItemCursor parseVerified(byte[] body) {
        ByteBuffer buffer = ByteBuffer.wrap(body);
        if (buffer.get() != VERSION) {
            throw invalidCursor();
        }
        // The key id was already resolved and checked while verifying the signature.
        readText(buffer, Byte.toUnsignedInt(buffer.get()), 32);
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
        Optional<UtcTimestamp> primaryTime = readOptionalInstant(buffer).map(UtcTimestamp::from);
        OptionalInt primaryRank = readOptionalInt(buffer);
        WorkItemId id = new WorkItemId(readUuid(buffer));
        if (buffer.hasRemaining()) {
            throw invalidCursor();
        }
        return new WorkItemCursor(scope, primaryTime, primaryRank, id);
    }

    @Override
    protected void validateTime(WorkItemCursor cursor, byte[] body) {
        Instant issuedAt = parseIssuedAt(body);
        Instant now = clock().instant();
        if (issuedAt.isAfter(now.plus(FUTURE_SKEW))) {
            throw invalidCursor();
        }
        if (issuedAt.plus(maximumAge()).isBefore(now)) {
            throw new WorkItemCursorExpiredException();
        }
    }

    /** Reads back only the issue time; the signature has already been verified by the caller. */
    private Instant parseIssuedAt(byte[] body) {
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

    @Override
    protected ApiRequestException invalidCursor() {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_cursor",
                "Cursor is invalid or belongs to another WorkItem query",
                Map.of("parameter", "after"));
    }
}
