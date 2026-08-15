package io.crewscope.application.task;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;

/** Scope-closed Task collection query with optional Project and status filters. */
public record TaskListQuery(
        OrganizationId organizationId,
        TeamId teamId,
        Optional<WorkProjectId> projectId,
        Optional<TaskStatus> status,
        Optional<TaskListCursor> cursor,
        int limit) {

    public TaskListQuery {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        projectId = Objects.requireNonNull(projectId, "projectId");
        status = Objects.requireNonNull(status, "status");
        cursor = Objects.requireNonNull(cursor, "cursor");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }
}
