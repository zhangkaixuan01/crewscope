package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.task.TaskEventCursor;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskEventCursorCodecTest {

    private final TaskEventCursorCodec codec = new TaskEventCursorCodec();

    @Test
    void roundTripsTheCompleteTaskRouteAndPosition() {
        TaskEventCursor cursor = new TaskEventCursor(
                OrganizationId.generate(), TeamId.generate(), TaskId.generate(), 42, UUID.randomUUID());

        assertEquals(
                cursor,
                codec.decode(
                        codec.encode(cursor),
                        cursor.organizationId(),
                        cursor.teamId(),
                        cursor.taskId()));
    }

    @Test
    void rejectsNonCanonicalAndCrossTaskTokens() {
        TaskEventCursor cursor = new TaskEventCursor(
                OrganizationId.generate(), TeamId.generate(), TaskId.generate(), 1, UUID.randomUUID());

        assertThrows(
                ApiRequestException.class,
                () -> codec.decode(
                        codec.encode(cursor),
                        cursor.organizationId(),
                        cursor.teamId(),
                        TaskId.generate()));
        assertThrows(
                ApiRequestException.class,
                () -> codec.decode(codec.encode(cursor) + "=", cursor.organizationId(),
                        cursor.teamId(), cursor.taskId()));
    }
}
