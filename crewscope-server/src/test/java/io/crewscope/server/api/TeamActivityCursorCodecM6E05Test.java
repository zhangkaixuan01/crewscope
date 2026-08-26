package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.activity.ActivityCursorScope;
import io.crewscope.application.activity.ActivityFilter;
import io.crewscope.application.activity.TeamActivityCursor;
import io.crewscope.application.activity.TeamActivityCursorExpiredException;
import io.crewscope.domain.activity.ActivityEventId;
import io.crewscope.domain.activity.TeamSequence;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HMAC, expiry, key rotation and complete Team Scope proof for the M6-E05 Cursor. */
class TeamActivityCursorCodecM6E05Test {

  private static final Instant NOW = Instant.parse("2026-08-26T01:00:00Z");
  private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
  private static final TeamId TEAM_ID = TeamId.generate();
  private static final ProjectionName PROJECTION_NAME = new ProjectionName("team-activity");
  private static final ActivityFilter FILTER = ActivityFilter.ALL;
  private static final String KEY_1 = encodedKey(11);
  private static final String KEY_2 = encodedKey(47);

  @Test
  void roundTripsEveryScopeCoordinateAndPosition() {
    TeamActivityCursor source = cursor(ORGANIZATION_ID, TEAM_ID, FILTER, 7, 3, 99);
    TeamActivityCursorCodec codec = codecAt(NOW, "k1", Map.of("k1", KEY_1));

    TeamActivityCursor decoded = codec.decode(
        codec.encode(source), ORGANIZATION_ID, TEAM_ID, PROJECTION_NAME, FILTER);

    assertEquals(source, decoded);
  }

  @Test
  void rejectsTamperingAndCrossScopeOrFilterReplay() {
    TeamActivityCursorCodec codec = codecAt(NOW, "k1", Map.of("k1", KEY_1));
    String token = codec.encode(cursor(ORGANIZATION_ID, TEAM_ID, FILTER, 1, 1, 4));
    char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';
    String tampered = token.substring(0, token.length() - 1) + replacement;

    assertInvalid(() -> codec.decode(
        tampered, ORGANIZATION_ID, TEAM_ID, PROJECTION_NAME, FILTER));
    assertInvalid(() -> codec.decode(
        token, OrganizationId.generate(), TEAM_ID, PROJECTION_NAME, FILTER));
    assertInvalid(() -> codec.decode(
        token, ORGANIZATION_ID, TeamId.generate(), PROJECTION_NAME, FILTER));
    assertInvalid(() -> codec.decode(
        token,
        ORGANIZATION_ID,
        TEAM_ID,
        PROJECTION_NAME,
        new ActivityFilter(java.util.Optional.empty(), java.util.Set.of(),
            java.util.Set.of(new io.crewscope.domain.shared.event.EventType("TASK_STARTED")),
            java.util.Set.of())));
  }

  @Test
  void returnsCursorExpiredOnlyAfterAValidSignatureWasVerified() {
    TeamActivityCursor source = cursor(ORGANIZATION_ID, TEAM_ID, FILTER, 1, 1, 5);
    String token = codecAt(NOW, "k1", Map.of("k1", KEY_1)).encode(source);
    TeamActivityCursorCodec later =
        codecAt(NOW.plus(Duration.ofHours(25)), "k1", Map.of("k1", KEY_1));

    assertThrows(
        TeamActivityCursorExpiredException.class,
        () -> later.decode(token, ORGANIZATION_ID, TEAM_ID, PROJECTION_NAME, FILTER));

    String tampered = token.substring(0, token.length() - 2) + "AA";
    assertInvalid(() -> later.decode(
        tampered, ORGANIZATION_ID, TEAM_ID, PROJECTION_NAME, FILTER));
  }

  @Test
  void verifiesBoundedOldKeysAfterRotationAndRejectsRemovedKeys() {
    TeamActivityCursor source = cursor(ORGANIZATION_ID, TEAM_ID, FILTER, 2, 1, 8);
    String oldToken = codecAt(NOW, "k1", Map.of("k1", KEY_1)).encode(source);
    LinkedHashMap<String, String> rotating = new LinkedHashMap<>();
    rotating.put("k1", KEY_1);
    rotating.put("k2", KEY_2);
    TeamActivityCursorCodec rotated = codecAt(NOW, "k2", rotating);

    assertEquals(
        source,
        rotated.decode(oldToken, ORGANIZATION_ID, TEAM_ID, PROJECTION_NAME, FILTER));
    TeamActivityCursorCodec removed = codecAt(NOW, "k2", Map.of("k2", KEY_2));
    assertInvalid(() -> removed.decode(
        oldToken, ORGANIZATION_ID, TEAM_ID, PROJECTION_NAME, FILTER));
  }

  private static TeamActivityCursor cursor(
      OrganizationId organizationId,
      TeamId teamId,
      ActivityFilter filter,
      long generation,
      int schema,
      long sequence) {
    ActivityCursorScope scope = ActivityCursorScope.of(
        organizationId,
        teamId,
        PROJECTION_NAME,
        new ProjectionGeneration(generation),
        new SchemaVersion(schema),
        filter);
    return new TeamActivityCursor(
        scope, new TeamSequence(sequence), new ActivityEventId(UUID.randomUUID()));
  }

  private static TeamActivityCursorCodec codecAt(
      Instant instant, String currentKeyId, Map<String, String> keys) {
    return new TeamActivityCursorCodec(
        new TeamActivityCursorKeyRing(currentKeyId, keys),
        Clock.fixed(instant, ZoneOffset.UTC),
        Duration.ofHours(24),
        Duration.ofSeconds(30));
  }

  private static String encodedKey(int seed) {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) {
      value[index] = (byte) (seed + index);
    }
    return Base64.getEncoder().encodeToString(value);
  }

  private static void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
    ApiRequestException failure = assertThrows(ApiRequestException.class, executable);
    assertEquals("invalid_cursor", failure.code());
  }
}
