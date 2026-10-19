package io.crewscope.server.api;

import io.crewscope.application.activity.ActivityCursorScope;
import io.crewscope.application.activity.ActivityFilter;
import io.crewscope.application.activity.ActivityFilterFingerprint;
import io.crewscope.application.activity.TeamActivityCursor;
import io.crewscope.application.activity.TeamActivityCursorExpiredException;
import io.crewscope.domain.activity.ActivityEventId;
import io.crewscope.domain.activity.TeamSequence;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/** Versioned HMAC-SHA256 codec binding a Team position to route, Generation and filter. */
public final class TeamActivityCursorCodec extends CursorCodec<TeamActivityCursor> {

  private static final byte VERSION = 1;
  private static final int FINGERPRINT_BYTES = 32;
  private static final int MAX_TOKEN_LENGTH = 768;

  private final Duration futureSkew;

  public TeamActivityCursorCodec(
      TeamActivityCursorKeyRing keyRing,
      Clock clock,
      Duration maximumAge,
      Duration futureSkew) {
    super(keyRing, clock, maximumAge, MAX_TOKEN_LENGTH);
    this.futureSkew = requireFutureSkew(futureSkew);
  }

  public TeamActivityCursor decode(
      String token,
      OrganizationId expectedOrganizationId,
      TeamId expectedTeamId,
      ProjectionName expectedProjectionName,
      ActivityFilter expectedFilter) {
    TeamActivityCursor cursor = decodeVerified(token);
    ActivityCursorScope scope = cursor.scope();
    ActivityFilter filter = Objects.requireNonNull(expectedFilter, "expectedFilter");
    if (!scope.organizationId().equals(Objects.requireNonNull(
            expectedOrganizationId, "expectedOrganizationId"))
        || !scope.teamId().equals(Objects.requireNonNull(expectedTeamId, "expectedTeamId"))
        || !scope.projectionName().equals(Objects.requireNonNull(
            expectedProjectionName, "expectedProjectionName"))
        || !scope.filterFingerprint().equals(filter.fingerprint())) {
      throw invalidCursor();
    }
    return cursor;
  }

  @Override
  protected byte version() {
    return VERSION;
  }

  /** The v1 wire format has no signing-domain prefix; the version byte separates surfaces. */
  @Override
  protected byte[] signingDomain() {
    return null;
  }

  @Override
  protected Duration requireMaximumAge(Duration maximumAge) {
    Duration required = Objects.requireNonNull(maximumAge, "maximumAge");
    if (required.compareTo(Duration.ofSeconds(1)) < 0
        || required.compareTo(Duration.ofDays(30)) > 0) {
      throw new IllegalArgumentException(
          "maximumAge must be at least one second and at most " + Duration.ofDays(30));
    }
    return required;
  }

  @Override
  protected byte[] encodeBody(
      TeamActivityCursor cursor, String keyId, Instant issuedAt) {
    byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);
    byte[] projectionBytes =
        cursor.scope().projectionName().value().getBytes(StandardCharsets.UTF_8);
    byte[] fingerprint =
        HexFormat.of().parseHex(cursor.scope().filterFingerprint().value());
    Instant expiresAt = issuedAt.plus(maximumAge());
    int size =
        1
            + 1
            + keyIdBytes.length
            + Short.BYTES
            + projectionBytes.length
            + (2 * Long.BYTES)
            + (2 * 2 * Long.BYTES)
            + Long.BYTES
            + Integer.BYTES
            + FINGERPRINT_BYTES
            + Long.BYTES
            + (2 * Long.BYTES);
    ByteBuffer buffer = ByteBuffer.allocate(size);
    buffer.put(VERSION).put((byte) keyIdBytes.length).put(keyIdBytes);
    buffer.putShort((short) projectionBytes.length).put(projectionBytes);
    buffer.putLong(issuedAt.getEpochSecond()).putLong(expiresAt.getEpochSecond());
    putUuid(buffer, cursor.scope().organizationId().value());
    putUuid(buffer, cursor.scope().teamId().value());
    buffer.putLong(cursor.scope().projectionGeneration().value());
    buffer.putInt(cursor.scope().projectionSchemaVersion().value());
    buffer.put(fingerprint);
    buffer.putLong(cursor.teamSequence().value());
    putUuid(buffer, cursor.eventId().value());
    return buffer.array();
  }

  @Override
  protected TeamActivityCursor parseVerified(byte[] body) {
    ByteBuffer buffer = ByteBuffer.wrap(body);
    if (buffer.get() != VERSION) {
      throw invalidCursor();
    }
    // The key id was already resolved and checked while verifying the signature.
    readText(buffer, Byte.toUnsignedInt(buffer.get()), 32);
    String projectionName = readText(buffer, Short.toUnsignedInt(buffer.getShort()), 180);
    buffer.getLong();
    buffer.getLong();
    OrganizationId organizationId = new OrganizationId(readUuid(buffer));
    TeamId teamId = new TeamId(readUuid(buffer));
    ProjectionGeneration generation = new ProjectionGeneration(buffer.getLong());
    SchemaVersion schemaVersion = new SchemaVersion(buffer.getInt());
    byte[] fingerprintBytes = new byte[FINGERPRINT_BYTES];
    buffer.get(fingerprintBytes);
    ActivityFilterFingerprint fingerprint =
        new ActivityFilterFingerprint(HexFormat.of().formatHex(fingerprintBytes));
    TeamSequence sequence = new TeamSequence(buffer.getLong());
    ActivityEventId eventId = new ActivityEventId(readUuid(buffer));
    if (buffer.hasRemaining()) {
      throw invalidCursor();
    }
    ActivityCursorScope scope =
        new ActivityCursorScope(
            organizationId,
            teamId,
            new ProjectionName(projectionName),
            generation,
            schemaVersion,
            fingerprint);
    return new TeamActivityCursor(scope, sequence, eventId);
  }

  @Override
  protected void validateTime(TeamActivityCursor cursor, byte[] body) {
    Times times;
    try {
      times = readTimes(body);
    } catch (BufferUnderflowException | DateTimeException | IllegalArgumentException failure) {
      throw invalidCursor();
    }
    validateTime(times.issuedAt(), times.expiresAt());
  }

  private void validateTime(Instant issuedAt, Instant expiresAt) {
    Instant now = clock().instant();
    if (!expiresAt.isAfter(issuedAt)
        || Duration.between(issuedAt, expiresAt).compareTo(maximumAge()) > 0
        || issuedAt.isAfter(now.plus(futureSkew))) {
      throw invalidCursor();
    }
    if (!expiresAt.isAfter(now)) {
      throw new TeamActivityCursorExpiredException();
    }
  }

  /** Reads back both timestamps; the signature has already been verified by the caller. */
  private static Times readTimes(byte[] body) {
    ByteBuffer buffer = ByteBuffer.wrap(body);
    buffer.get();
    buffer.position(buffer.position() + 1 + Byte.toUnsignedInt(buffer.get()));
    buffer.position(buffer.position() + Short.BYTES + Short.toUnsignedInt(buffer.getShort()));
    return new Times(
        Instant.ofEpochSecond(buffer.getLong()), Instant.ofEpochSecond(buffer.getLong()));
  }

  private static Duration requireFutureSkew(Duration futureSkew) {
    Duration required = Objects.requireNonNull(futureSkew, "futureSkew");
    if (required.compareTo(Duration.ofSeconds(1)) < 0
        || required.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException(
          "futureSkew must be at least one second and at most " + Duration.ofMinutes(5));
    }
    return required;
  }

  @Override
  protected ApiRequestException invalidCursor() {
    return new ApiRequestException(
        HttpStatus.BAD_REQUEST,
        "invalid_cursor",
        "Cursor is invalid or belongs to another Team Activity stream",
        Map.of("parameter", "after"));
  }

  private record Times(Instant issuedAt, Instant expiresAt) {}
}
