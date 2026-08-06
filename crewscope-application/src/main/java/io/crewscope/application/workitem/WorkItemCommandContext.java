package io.crewscope.application.workitem;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.util.Objects;

/**
 * Server-resolved identity and scope for a WorkItem command.
 *
 * <p>HTTP, AG-UI and Agent tool adapters build this context after authentication and authorization;
 * client request bodies never supply these trusted facts directly.
 */
public record WorkItemCommandContext(
        OrganizationId organizationId,
        TeamId teamId,
        WorkspaceId workspaceId,
        PrincipalId actorId) {

    public WorkItemCommandContext {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        actorId = Objects.requireNonNull(actorId, "actorId");
    }
}
