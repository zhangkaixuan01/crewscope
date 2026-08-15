package io.crewscope.application.task;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskId;
import java.util.Objects;
import java.util.UUID;

/** Scope-bound durable position in one Task Event stream. */
public record TaskEventCursor(
        OrganizationId organizationId,
        TeamId teamId,
        TaskId taskId,
        long position,
        UUID eventId) {

    public TaskEventCursor {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        taskId = Objects.requireNonNull(taskId, "taskId");
        if (position < 1) {
            throw new IllegalArgumentException("position must be positive");
        }
        eventId = Objects.requireNonNull(eventId, "eventId");
    }

    /** Fails closed when a Cursor is replayed on another Task route. */
    public TaskEventCursor requireStream(
            OrganizationId expectedOrganizationId,
            TeamId expectedTeamId,
            TaskId expectedTaskId) {
        if (!organizationId.equals(Objects.requireNonNull(
                        expectedOrganizationId, "expectedOrganizationId"))
                || !teamId.equals(Objects.requireNonNull(expectedTeamId, "expectedTeamId"))
                || !taskId.equals(Objects.requireNonNull(expectedTaskId, "expectedTaskId"))) {
            throw new IllegalArgumentException("cursor must belong to the requested Task stream");
        }
        return this;
    }
}
