package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.coding.query.CodingEvidenceCursor;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Scope and collection binding contract for M4-A04 opaque evidence cursors. */
class CodingEvidenceCursorCodecM4A04Test {

    private final CodingEvidenceCursorCodec codec = new CodingEvidenceCursorCodec();
    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final TaskId taskId = TaskId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();

    @Test
    void roundTripsOneCanonicalKeyset() {
        CodingEvidenceCursor cursor = new CodingEvidenceCursor(42, UUID.randomUUID());
        String token = codec.encode(cursor, organizationId, teamId, taskId, executionId,
                CodingEvidenceCursorCodec.Collection.COMMANDS);

        assertEquals(cursor, codec.decode(token, organizationId, teamId, taskId, executionId,
                CodingEvidenceCursorCodec.Collection.COMMANDS));
    }

    @Test
    void rejectsReuseAcrossTenantTaskAttemptAndCollection() {
        String token = codec.encode(new CodingEvidenceCursor(1, UUID.randomUUID()),
                organizationId, teamId, taskId, executionId,
                CodingEvidenceCursorCodec.Collection.COMMANDS);

        assertThrows(ApiRequestException.class, () -> codec.decode(token,
                OrganizationId.generate(), teamId, taskId, executionId,
                CodingEvidenceCursorCodec.Collection.COMMANDS));
        assertThrows(ApiRequestException.class, () -> codec.decode(token,
                organizationId, TeamId.generate(), taskId, executionId,
                CodingEvidenceCursorCodec.Collection.COMMANDS));
        assertThrows(ApiRequestException.class, () -> codec.decode(token,
                organizationId, teamId, TaskId.generate(), executionId,
                CodingEvidenceCursorCodec.Collection.COMMANDS));
        assertThrows(ApiRequestException.class, () -> codec.decode(token,
                organizationId, teamId, taskId, TaskExecutionId.generate(),
                CodingEvidenceCursorCodec.Collection.COMMANDS));
        assertThrows(ApiRequestException.class, () -> codec.decode(token,
                organizationId, teamId, taskId, executionId,
                CodingEvidenceCursorCodec.Collection.TEST_EVIDENCE));
    }
}
