package io.crewscope.domain.workitem;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.util.Objects;

/** Immutable tenant and project ownership of a WorkItem. */
public record WorkItemScope(
        OrganizationId organizationId,
        TeamId teamId,
        WorkspaceId workspaceId,
        WorkProjectId projectId) {

    public WorkItemScope {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        projectId = Objects.requireNonNull(projectId, "projectId");
    }
}
