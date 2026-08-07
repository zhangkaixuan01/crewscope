package io.crewscope.application.artifact;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.util.Objects;
import java.util.Set;

/** Authenticated Principal and the tenant scopes already granted by the authorization layer. */
public record ArtifactAccessContext(
        OrganizationId organizationId,
        PrincipalId principalId,
        Set<TeamId> authorizedTeamIds,
        Set<WorkspaceId> authorizedWorkspaceIds) {

    public ArtifactAccessContext {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(principalId, "principalId");
        authorizedTeamIds = Set.copyOf(
                Objects.requireNonNull(authorizedTeamIds, "authorizedTeamIds"));
        authorizedWorkspaceIds = Set.copyOf(
                Objects.requireNonNull(authorizedWorkspaceIds, "authorizedWorkspaceIds"));
    }

    /** Applies the descriptor's visibility after enforcing its organization boundary. */
    public boolean allows(ArtifactDescriptor descriptor) {
        ArtifactDescriptor artifact = Objects.requireNonNull(descriptor, "descriptor");
        if (!organizationId.equals(artifact.scope().organizationId())) {
            return false;
        }
        return switch (artifact.visibility()) {
            case PRIVATE -> principalId.equals(artifact.producer().principalId());
            case WORKSPACE -> artifact.scope().workspaceId()
                    .map(authorizedWorkspaceIds::contains)
                    .orElse(false);
            case TEAM -> artifact.scope().teamId()
                    .map(authorizedTeamIds::contains)
                    .orElse(false);
            case ORGANIZATION -> true;
        };
    }
}
