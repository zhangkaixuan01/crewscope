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
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/** Versioned HMAC-SHA256 codec binding a Team position to route, Generation and filter. */
public final class TeamActivityCursorCodec {

  private static final byte VERSION = 1;
  private static final int SIGNATURE_BYTES = 32;
  private static final int FINGERPRINT_BYTES = 32;
  private static final int MAX_TOKEN_LENGTH = 768;
  private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]+");

  private final TeamActivityCursorKeyRing keyRing;
  private final Clock clock;
  private final Duration maximumAge;
  private final Duration futureSkew;

  public TeamActivityCursorCodec(
      TeamActivityCursorKeyRing keyRing,
      Clock clock,
      Duration maximumAge,
      Duration futureSkew) {
    this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.maximumAge = requireDuration(maximumAge, "maximumAge", Duration.ofDays(30));
    this.futureSkew = requireDuration(futureSkew, "futureSkew", Duration.ofMinutes(5));
  }

  public String encode(TeamActivityCursor cursor) {
    TeamActivityCursor source = Objects.requireNonNull(cursor, "cursor");
    Instant issuedAt = clock.instant();
    Instant expiresAt = issuedAt.plus(maximumAge);
    byte[] body = encodeBody(source, keyRing.currentKeyId(), issuedAt, expiresAt);
    byte[] signature = sign(body, keyRing.currentKey());
    ByteBuffer token = ByteBuffer.allocate(body.length + signature.length);
    token.put(body).put(signature);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token.array());
  }

  public TeamActivityCursor decode(
      String token,
      OrganizationId expectedOrganizationId,
      TeamId expectedTeamId,
      ProjectionName expectedProjectionName,
      ActivityFilter expectedFilter) {
    byte[] encoded = decodeCanonicalToken(token);
    if (encoded.length <= SIGNATURE_BYTES) {
      throw invalidCursor();
    }
    byte[] body = Arrays.copyOf(encoded, encoded.length - SIGNATURE_BYTES);
    byte[] signature = Arrays.copyOfRange(encoded, body.length, encoded.length);
    String keyId = readKeyId(body);
    byte[] key = keyRing.key(keyId);
    if (key == null || !MessageDigest.isEqual(signature, sign(body, key))) {
      throw invalidCursor();
    }
    Decoded decoded = parseVerified(body);
    validateTime(decoded.issuedAt(), decoded.expiresAt());
    ActivityCursorScope scope = decoded.cursor().scope();
    ActivityFilter filter = Objects.requireNonNull(expectedFilter, "expectedFilter");
    if (!scope.organizationId().equals(Objects.requireNonNull(
            expectedOrganizationId, "expectedOrganizationId"))
        || !scope.teamId().equals(Objects.requireNonNull(expectedTeamId, "expectedTeamId"))
        || !scope.projectionName().equals(Objects.requireNonNull(
            expectedProjectionName, "expectedProjectionName"))
        || !scope.filterFingerprint().equals(filter.fingerprint())) {
      throw invalidCursor();
    }
    return decoded.cursor();
  }

  private byte[] encodeBody(
      TeamActivityCursor cursor, String keyId, Instant issuedAt, Instant expiresAt) {
    byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);
    byte[] projectionBytes =
        cursor.scope().projectionName().value().getBytes(StandardCharsets.UTF_8);
    byte[] fingerprint =
        HexFormat.of().parseHex(cursor.scope().filterFingerprint().value());
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

  private Decoded parseVerified(byte[] body) {
    try {
      ByteBuffer buffer = ByteBuffer.wrap(body);
      if (buffer.get() != VERSION) {
        throw invalidCursor();
      }
      String keyId = readText(buffer, Byte.toUnsignedInt(buffer.get()), 32);
      if (!keyId.equals(keyRing.currentKeyId()) && keyRing.key(keyId) == null) {
        throw invalidCursor();
      }
      String projectionName = readText(buffer, Short.toUnsignedInt(buffer.getShort()), 180);
      Instant issuedAt = Instant.ofEpochSecond(buffer.getLong());
      Instant expiresAt = Instant.ofEpochSecond(buffer.getLong());
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
      return new Decoded(new TeamActivityCursor(scope, sequence, eventId), issuedAt, expiresAt);
    } catch (ApiRequestException failure) {
      throw failure;
    } catch (BufferUnderflowException | DateTimeException | IllegalArgumentException failure) {
      throw invalidCursor();
    }
  }

  private void validateTime(Instant issuedAt, Instant expiresAt) {
    Instant now = clock.instant();
    if (!expiresAt.isAfter(issuedAt)
        || Duration.between(issuedAt, expiresAt).compareTo(maximumAge) > 0
        || issuedAt.isAfter(now.plus(futureSkew))) {
      throw invalidCursor();
    }
    if (!expiresAt.isAfter(now)) {
      throw new TeamActivityCursorExpiredException();
    }
  }

  private static byte[] decodeCanonicalToken(String token) {
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
    byte[] value = new byte[length];
    buffer.get(value);
    String decoded = new String(value, StandardCharsets.UTF_8);
    if (!Arrays.equals(value, decoded.getBytes(StandardCharsets.UTF_8))) {
      throw invalidCursor();
    }
    return decoded;
  }

  private static void putUuid(ByteBuffer buffer, UUID value) {
    buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
  }

  private static UUID readUuid(ByteBuffer buffer) {
    return new UUID(buffer.getLong(), buffer.getLong());
  }

  private static byte[] sign(byte[] body, byte[] key) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(body);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("HmacSHA256 is unavailable", failure);
    }
  }

  private static Duration requireDuration(
      Duration value, String name, Duration maximum) {
    Duration required = Objects.requireNonNull(value, name);
    if (required.compareTo(Duration.ofSeconds(1)) < 0 || required.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(
          name + " must be at least one second and at most " + maximum);
    }
    return required;
  }

  private static ApiRequestException invalidCursor() {
    return new ApiRequestException(
        HttpStatus.BAD_REQUEST,
        "invalid_cursor",
        "Cursor is invalid or belongs to another Team Activity stream",
        Map.of("parameter", "after"));
  }

  private record Decoded(
      TeamActivityCursor cursor, Instant issuedAt, Instant expiresAt) {}
}
