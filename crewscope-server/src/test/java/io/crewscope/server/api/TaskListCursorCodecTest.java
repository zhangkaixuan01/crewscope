package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.task.TaskListCursor;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Opaque Task keyset transport contract. */
class TaskListCursorCodecTest {

    private final TaskListCursorCodec codec = new TaskListCursorCodec();
    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();

    @Test
    void roundTripsTheExactNanosecondAndTaskId() {
        TaskListCursor cursor = new TaskListCursor(
                UtcTimestamp.parse("2026-08-15T08:09:10.123456789Z"), TaskId.generate());
        String token = codec.encode(
                cursor,
                organizationId,
                teamId,
                Optional.of(projectId),
                Optional.of(TaskStatus.ACTIVE));
        assertEquals(cursor, codec.decode(
                token,
                organizationId,
                teamId,
                Optional.of(projectId),
                Optional.of(TaskStatus.ACTIVE)));
    }

    @Test
    void rejectsTokensReplayedAcrossTenantRouteOrFilters() {
        TaskListCursor cursor = new TaskListCursor(
                UtcTimestamp.parse("2026-08-15T08:00:00Z"), TaskId.generate());
        String token = codec.encode(
                cursor,
                organizationId,
                teamId,
                Optional.of(projectId),
                Optional.of(TaskStatus.ACTIVE));

        assertThrows(ApiRequestException.class, () -> codec.decode(
                token,
                organizationId,
                TeamId.generate(),
                Optional.of(projectId),
                Optional.of(TaskStatus.ACTIVE)));
        assertThrows(ApiRequestException.class, () -> codec.decode(
                token,
                organizationId,
                teamId,
                Optional.empty(),
                Optional.of(TaskStatus.ACTIVE)));
        assertThrows(ApiRequestException.class, () -> codec.decode(
                token,
                organizationId,
                teamId,
                Optional.of(projectId),
                Optional.of(TaskStatus.FAILED)));
    }

    @Test
    void rejectsNonCanonicalMalformedAndUnsupportedTokens() {
        assertThrows(ApiRequestException.class, () -> decode(" "));
        assertThrows(ApiRequestException.class, () -> decode("not+base64"));
        String valid = codec.encode(
                new TaskListCursor(
                        UtcTimestamp.parse("2026-08-15T08:00:00Z"), TaskId.generate()),
                organizationId,
                teamId,
                Optional.empty(),
                Optional.empty());
        byte[] decoded = java.util.Base64.getUrlDecoder().decode(valid);
        decoded[0] = 99;
        assertThrows(ApiRequestException.class, () -> decode(
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)));
    }

    private TaskListCursor decode(String token) {
        return codec.decode(
                token, organizationId, teamId, Optional.empty(), Optional.empty());
    }
}
