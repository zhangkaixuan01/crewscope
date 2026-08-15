package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.task.TaskAssociationCursor;
import io.crewscope.application.task.TaskAssociationSourceType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Transport and route-binding contract for M3-A06 association cursors. */
class TaskAssociationCursorCodecTest {

    private final TaskAssociationCursorCodec codec = new TaskAssociationCursorCodec();

    @Test
    void roundTripsTheCompleteAssociationSourceAndKeyset() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        UUID sourceId = UUID.randomUUID();
        TaskAssociationCursor cursor = new TaskAssociationCursor(
                organizationId,
                teamId,
                TaskAssociationSourceType.CONVERSATION,
                sourceId,
                UtcTimestamp.parse("2026-08-15T10:15:30.123456Z"),
                UUID.randomUUID());

        assertEquals(cursor, codec.decode(
                codec.encode(cursor),
                organizationId,
                teamId,
                TaskAssociationSourceType.CONVERSATION,
                sourceId));
    }

    @Test
    void refusesReplayOnAnotherTeamTypeOrSourceObject() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        UUID sourceId = UUID.randomUUID();
        String token = codec.encode(new TaskAssociationCursor(
                organizationId,
                teamId,
                TaskAssociationSourceType.TASK,
                sourceId,
                UtcTimestamp.parse("2026-08-15T10:15:30Z"),
                UUID.randomUUID()));

        assertThrows(ApiRequestException.class, () -> codec.decode(
                token,
                organizationId,
                TeamId.generate(),
                TaskAssociationSourceType.TASK,
                sourceId));
        assertThrows(ApiRequestException.class, () -> codec.decode(
                token,
                organizationId,
                teamId,
                TaskAssociationSourceType.WORK_ITEM,
                sourceId));
        assertThrows(ApiRequestException.class, () -> codec.decode(
                token,
                organizationId,
                teamId,
                TaskAssociationSourceType.TASK,
                UUID.randomUUID()));
    }

    @Test
    void rejectsMalformedAndNonCanonicalTokens() {
        assertThrows(ApiRequestException.class, () -> codec.decode(
                "not+canonical",
                OrganizationId.generate(),
                TeamId.generate(),
                TaskAssociationSourceType.WORK_ITEM,
                UUID.randomUUID()));
    }
}
