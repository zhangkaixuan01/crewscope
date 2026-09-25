package io.crewscope.domain.coding;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Objects;
import java.util.Optional;

/** Versioned project defaults used only when a new execution has no explicit selection. */
public record ProjectExecutionDefaults(
        OrganizationId organizationId,
        TeamId teamId,
        WorkspaceId workspaceId,
        WorkProjectId projectId,
        long version,
        Optional<RepositoryBindingId> repositoryBindingId,
        Optional<Long> repositoryBindingVersion,
        Optional<RepositoryBranchName> branch,
        Optional<BuildProfileReference> buildProfile,
        Optional<AgentProfileId> agentProfileId,
        Optional<Long> agentProfileRevision) {

    public ProjectExecutionDefaults {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        projectId = Objects.requireNonNull(projectId, "projectId");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        repositoryBindingId = Objects.requireNonNull(repositoryBindingId, "repositoryBindingId");
        repositoryBindingVersion = Objects.requireNonNull(repositoryBindingVersion, "repositoryBindingVersion");
        branch = Objects.requireNonNull(branch, "branch");
        buildProfile = Objects.requireNonNull(buildProfile, "buildProfile");
        agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        agentProfileRevision = Objects.requireNonNull(agentProfileRevision, "agentProfileRevision");
        if (repositoryBindingId.isEmpty() != repositoryBindingVersion.isEmpty()) {
            throw new IllegalArgumentException("repository binding id and version must be set together");
        }
        if (branch.isPresent() && repositoryBindingId.isEmpty()) {
            throw new IllegalArgumentException("branch requires a repository binding");
        }
        if (agentProfileRevision.isEmpty() != agentProfileId.isEmpty()) {
            throw new IllegalArgumentException("agent profile and revision must be set together");
        }
        repositoryBindingVersion.ifPresent(value -> requireNonNegative(value, "repositoryBindingVersion"));
        agentProfileRevision.ifPresent(value -> requireNonNegative(value, "agentProfileRevision"));
    }

    public static ProjectExecutionDefaults empty(
            OrganizationId organizationId, TeamId teamId, WorkspaceId workspaceId, WorkProjectId projectId) {
        return new ProjectExecutionDefaults(
                organizationId, teamId, workspaceId, projectId, 0,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    public ProjectExecutionDefaults next(
            Optional<RepositoryBindingId> repositoryBindingId,
            Optional<Long> repositoryBindingVersion,
            Optional<RepositoryBranchName> branch,
            Optional<BuildProfileReference> buildProfile,
            Optional<AgentProfileId> agentProfileId,
            Optional<Long> agentProfileRevision) {
        return new ProjectExecutionDefaults(
                organizationId, teamId, workspaceId, projectId, version + 1,
                repositoryBindingId, repositoryBindingVersion, branch, buildProfile,
                agentProfileId, agentProfileRevision);
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must not be negative");
    }
}
