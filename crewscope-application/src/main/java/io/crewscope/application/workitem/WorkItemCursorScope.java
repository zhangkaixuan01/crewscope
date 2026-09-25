package io.crewscope.application.workitem;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;

/**
 * Complete tenant, ordering and filter scope bound into a WorkItem list cursor (M9b-A06).
 *
 * <p>The viewer is part of the scope, so a token minted for one member's query cannot be replayed
 * against another member's view even inside the same Team. The sort is part of it because a keyset
 * position only means something for the ordering that produced it.
 */
public record WorkItemCursorScope(
        OrganizationId organizationId,
        TeamId teamId,
        WorkProjectId projectId,
        PrincipalId viewerPrincipalId,
        WorkItemSort sort,
        WorkItemFilterFingerprint filterFingerprint) {

    public WorkItemCursorScope {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        projectId = Objects.requireNonNull(projectId, "projectId");
        viewerPrincipalId = Objects.requireNonNull(viewerPrincipalId, "viewerPrincipalId");
        sort = Objects.requireNonNull(sort, "sort");
        filterFingerprint = Objects.requireNonNull(filterFingerprint, "filterFingerprint");
    }

    public static WorkItemCursorScope of(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            PrincipalId viewerPrincipalId,
            WorkItemSort sort,
            WorkItemFilter filter) {
        return new WorkItemCursorScope(
                organizationId,
                teamId,
                projectId,
                viewerPrincipalId,
                Objects.requireNonNull(sort, "sort"),
                Objects.requireNonNull(filter, "filter").fingerprint());
    }
}
