package io.crewscope.domain.provider;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectStatus;
import io.crewscope.domain.workspace.Workspace;
import io.crewscope.domain.workspace.WorkspaceStatus;
import io.crewscope.domain.workspace.WorkspaceType;
import java.util.Objects;
import java.util.Optional;

/** Team Workspace or WorkProject boundary to which one Provider implementation is bound. */
public record ProviderBindingTarget(
        OrganizationId organizationId,
        TeamId teamId,
        WorkspaceId workspaceId,
        ProviderBindingTargetType type,
        Optional<WorkProjectId> workProjectId) {

    public ProviderBindingTarget {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        type = Objects.requireNonNull(type, "type");
        workProjectId = Objects.requireNonNull(workProjectId, "workProjectId");
        if ((type == ProviderBindingTargetType.WORKSPACE) == workProjectId.isPresent()) {
            throw new DomainValidationException(
                    "providerBindingTarget.workProjectId",
                    "must be present exactly for a WORK_PROJECT target");
        }
    }

    public static ProviderBindingTarget workspace(Workspace workspace) {
        Workspace required = requireActiveTeamWorkspace(workspace);
        return new ProviderBindingTarget(
                required.scope().organizationId(),
                required.scope().teamId().orElseThrow(),
                required.id(),
                ProviderBindingTargetType.WORKSPACE,
                Optional.empty());
    }

    public static ProviderBindingTarget workProject(WorkProject project) {
        WorkProject required = Objects.requireNonNull(project, "project");
        if (required.status() != WorkProjectStatus.ACTIVE) {
            throw new DomainValidationException(
                    "providerBindingTarget.workProjectId", "must be an active WorkProject");
        }
        return new ProviderBindingTarget(
                required.scope().organizationId(),
                required.scope().teamId(),
                required.scope().workspaceId(),
                ProviderBindingTargetType.WORK_PROJECT,
                Optional.of(required.id()));
    }

    private static Workspace requireActiveTeamWorkspace(Workspace workspace) {
        Workspace required = Objects.requireNonNull(workspace, "workspace");
        if (required.type() != WorkspaceType.TEAM
                || required.status() != WorkspaceStatus.ACTIVE) {
            throw new DomainValidationException(
                    "providerBindingTarget.workspaceId",
                    "must reference an active Team Workspace");
        }
        return required;
    }
}
