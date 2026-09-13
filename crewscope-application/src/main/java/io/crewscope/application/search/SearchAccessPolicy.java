package io.crewscope.application.search;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** Reuses the WorkItem membership authority; search introduces no separate permission model. */
public final class SearchAccessPolicy {
  private final WorkItemAccessPolicy delegate;
  public SearchAccessPolicy(WorkItemAccessPolicy delegate) { this.delegate = Objects.requireNonNull(delegate, "delegate"); }
  public io.crewscope.domain.team.TeamMember requireMember(TeamAccessContext context, OrganizationId organizationId, TeamId teamId) { return delegate.requireVisibleTeamMember(context, organizationId, teamId); }
}
