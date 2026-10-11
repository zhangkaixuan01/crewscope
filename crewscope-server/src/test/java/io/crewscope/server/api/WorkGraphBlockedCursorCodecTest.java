package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.workgraph.BlockedItemPosition;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cursor contract of the blocked-work aggregation (M11-A02): round-trips its keyset position,
 * binds the token to the Team and project filter, and rejects tampering, foreign streams and
 * stale issues as invalid cursors.
 */
class WorkGraphBlockedCursorCodecTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final UtcTimestamp BLOCKED_SINCE =
            UtcTimestamp.parse("2026-10-11T10:00:00.123456Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkItemId workItemId = WorkItemId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();

    private TeamActivityCursorKeyRing keyRing;

    @BeforeEach
    void setUp() {
        keyRing =
                new TeamActivityCursorKeyRing(
                        "test-key",
                        Map.of("test-key", Base64.getEncoder().encodeToString(new byte[48])));
    }

    private WorkGraphBlockedCursorCodec codec(Instant now) {
        return new WorkGraphBlockedCursorCodec(
                keyRing, Clock.fixed(now, ZoneOffset.UTC), Duration.ofDays(30));
    }

    @Test
    void roundTripsTheKeysetPositionInsideTheTeamScope() {
        BlockedItemPosition position = new BlockedItemPosition(BLOCKED_SINCE, workItemId);
        WorkGraphBlockedCursorCodec codec = codec(NOW);

        String token = codec.encode(organizationId, teamId, position);

        assertEquals(position, codec.decode(token, organizationId, teamId, Optional.empty()));
    }

    @Test
    void roundTripsWithTheProjectFilterBoundIntoTheScope() {
        BlockedItemPosition position = new BlockedItemPosition(BLOCKED_SINCE, workItemId);
        WorkGraphBlockedCursorCodec codec = codec(NOW);

        String token = codec.encode(organizationId, teamId, projectId, position);

        assertEquals(
                position,
                codec.decode(token, organizationId, teamId, Optional.of(projectId)));
        // the filter is part of the signed scope: the same token is not readable without it
        assertThrows(
                ApiRequestException.class,
                () -> codec.decode(token, organizationId, teamId, Optional.empty()));
    }

    @Test
    void foreignTeamsOrProjectsAreInvalidNotMerelyStale() {
        BlockedItemPosition position = new BlockedItemPosition(BLOCKED_SINCE, workItemId);
        WorkGraphBlockedCursorCodec codec = codec(NOW);
        String token = codec.encode(organizationId, teamId, position);

        assertThrows(
                ApiRequestException.class,
                () -> codec.decode(token, organizationId, TeamId.generate(), Optional.empty()));
        assertThrows(
                ApiRequestException.class,
                () ->
                        codec.decode(
                                token,
                                OrganizationId.generate(),
                                teamId,
                                Optional.empty()));
    }

    @Test
    void tamperedTokensAreRejected() {
        String token = codec(NOW).encode(organizationId, teamId, new BlockedItemPosition(BLOCKED_SINCE, workItemId));
        String tampered =
                token.substring(0, token.length() - 2)
                        + ("A".equals(token.substring(token.length() - 2, token.length() - 1))
                                ? "B"
                                : "A")
                        + token.substring(token.length() - 1);
        String otherDomain = token + "AAAA";

        assertThrows(
                ApiRequestException.class,
                () -> codec(NOW).decode(tampered, organizationId, teamId, Optional.empty()));
        assertThrows(
                ApiRequestException.class,
                () -> codec(NOW).decode(otherDomain, organizationId, teamId, Optional.empty()));
        assertThrows(
                ApiRequestException.class,
                () -> codec(NOW).decode("not a cursor", organizationId, teamId, Optional.empty()));
    }

    @Test
    void issuesOlderThanTheMaximumAgeAreRejected() {
        BlockedItemPosition position = new BlockedItemPosition(BLOCKED_SINCE, workItemId);
        String token = codec(NOW).encode(organizationId, teamId, position);

        assertThrows(
                ApiRequestException.class,
                () ->
                        codec(NOW.plus(Duration.ofDays(31)))
                                .decode(token, organizationId, teamId, Optional.empty()));
    }
}
