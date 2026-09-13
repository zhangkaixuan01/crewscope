package io.crewscope.application.workitem;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;

/** Fully scoped read query for transition discoverability of one WorkItem. */
public record WorkItemTransitionAvailabilityQuery(
    TeamAccessContext context,
    OrganizationId organizationId,
    TeamId teamId,
    WorkProjectId projectId,
    WorkItemId workItemId) {
  public WorkItemTransitionAvailabilityQuery {
    context = Objects.requireNonNull(context, "context");
    organizationId = Objects.requireNonNull(organizationId, "organizationId");
    teamId = Objects.requireNonNull(teamId, "teamId");
    projectId = Objects.requireNonNull(projectId, "projectId");
    workItemId = Objects.requireNonNull(workItemId, "workItemId");
  }
}
