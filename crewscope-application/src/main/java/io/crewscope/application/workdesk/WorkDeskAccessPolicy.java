package io.crewscope.application.workdesk;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;

/** Reuses the existing Team membership authority; no WorkDesk-specific authorization exists. */
public final class WorkDeskAccessPolicy {
  private final WorkItemAccessPolicy accessPolicy;

  public WorkDeskAccessPolicy(WorkItemAccessPolicy accessPolicy) {
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
  }

  public TeamMember requireMember(TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
    return accessPolicy.requireVisibleTeamMember(context, organizationId, teamId);
  }
}
