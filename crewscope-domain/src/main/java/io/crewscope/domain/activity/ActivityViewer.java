package io.crewscope.domain.activity;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Objects;
import java.util.Set;

/** Current authorization facts used to evaluate one Activity audience. */
public record ActivityViewer(
        OrganizationId organizationId,
        TeamId teamId,
        boolean activeTeamMember,
        boolean teamAdmin,
        Set<WorkItemId> visibleWorkItems) {

    public ActivityViewer {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        visibleWorkItems = Set.copyOf(Objects.requireNonNull(visibleWorkItems, "visibleWorkItems"));
        if (teamAdmin && !activeTeamMember) {
            throw new IllegalArgumentException("Team admin Activity viewer must be an active member");
        }
    }
}
