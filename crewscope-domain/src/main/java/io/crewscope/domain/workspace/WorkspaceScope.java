package io.crewscope.domain.workspace;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.Optional;

/** Organization ownership and optional Team boundary of a product Workspace. */
public record WorkspaceScope(
        OrganizationId organizationId, Optional<TeamId> teamId) {

    public WorkspaceScope {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
    }

    public static WorkspaceScope personal(OrganizationId organizationId) {
        return new WorkspaceScope(organizationId, Optional.empty());
    }

    public static WorkspaceScope team(OrganizationId organizationId, TeamId teamId) {
        return new WorkspaceScope(
                organizationId, Optional.of(Objects.requireNonNull(teamId, "teamId")));
    }

    public void validateFor(WorkspaceType type) {
        WorkspaceType requiredType = Objects.requireNonNull(type, "type");
        if (requiredType == WorkspaceType.PERSONAL && teamId.isPresent()) {
            throw new DomainValidationException(
                    "workspace.scope", "a PERSONAL Workspace must not reference a Team");
        }
        if (requiredType == WorkspaceType.TEAM && teamId.isEmpty()) {
            throw new DomainValidationException(
                    "workspace.scope", "a TEAM Workspace must reference a Team");
        }
    }
}
