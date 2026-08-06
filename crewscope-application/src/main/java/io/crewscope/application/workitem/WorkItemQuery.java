package io.crewscope.application.workitem;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;

/** Tenant-scoped WorkItem list query with optional Project, status and keyset cursor filters. */
public record WorkItemQuery(
        OrganizationId organizationId,
        TeamId teamId,
        Optional<WorkProjectId> projectId,
        Optional<WorkItemStatus> status,
        Optional<WorkItemCursor> cursor,
        int limit) {

    public WorkItemQuery {
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
