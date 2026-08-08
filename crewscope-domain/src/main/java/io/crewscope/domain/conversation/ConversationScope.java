package io.crewscope.domain.conversation;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.util.Objects;

/** Immutable Organization, Team and Workspace ownership of a Conversation. */
public record ConversationScope(
        OrganizationId organizationId, TeamId teamId, WorkspaceId workspaceId) {

    public ConversationScope {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }
}
