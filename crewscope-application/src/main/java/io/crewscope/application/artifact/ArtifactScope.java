package io.crewscope.application.artifact;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.util.Objects;
import java.util.Optional;

/** Tenant coordinates retained with an artifact and checked before every read. */
public record ArtifactScope(
        OrganizationId organizationId,
        Optional<TeamId> teamId,
        Optional<WorkspaceId> workspaceId) {

    public ArtifactScope {
        Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    public static ArtifactScope organization(OrganizationId organizationId) {
        return new ArtifactScope(organizationId, Optional.empty(), Optional.empty());
    }

    public static ArtifactScope team(OrganizationId organizationId, TeamId teamId) {
        return new ArtifactScope(organizationId, Optional.of(teamId), Optional.empty());
    }

    public static ArtifactScope workspace(
            OrganizationId organizationId,
            Optional<TeamId> teamId,
            WorkspaceId workspaceId) {
        return new ArtifactScope(organizationId, teamId, Optional.of(workspaceId));
    }

    /** Ensures that the selected visibility has coordinates that can be authorized. */
    public void validateVisibility(ArtifactVisibility visibility) {
        ArtifactVisibility value = Objects.requireNonNull(visibility, "visibility");
        if (value == ArtifactVisibility.TEAM && teamId.isEmpty()) {
            throw new IllegalArgumentException("TEAM visibility requires a team scope");
        }
        if (value == ArtifactVisibility.WORKSPACE && workspaceId.isEmpty()) {
            throw new IllegalArgumentException("WORKSPACE visibility requires a workspace scope");
        }
    }
}
