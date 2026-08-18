package io.crewscope.domain.coding;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;

/** Immutable tenant, Team Workspace and WorkProject ownership of a repository binding. */
public record RepositoryBindingScope(
        OrganizationId organizationId,
        TeamId teamId,
        WorkspaceId workspaceId,
        WorkProjectId workProjectId) {

    public RepositoryBindingScope {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        workProjectId = Objects.requireNonNull(workProjectId, "workProjectId");
    }

    public static RepositoryBindingScope from(WorkProject project) {
        WorkProject required = Objects.requireNonNull(project, "project");
        return new RepositoryBindingScope(
                required.scope().organizationId(),
                required.scope().teamId(),
                required.scope().workspaceId(),
                required.id());
    }
}
