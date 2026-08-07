package io.crewscope.domain.workitem;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.util.Objects;

/** Immutable Organization, Team and Workspace ownership of a WorkProject. */
public record WorkProjectScope(
        OrganizationId organizationId, TeamId teamId, WorkspaceId workspaceId) {

    public WorkProjectScope {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }
}
