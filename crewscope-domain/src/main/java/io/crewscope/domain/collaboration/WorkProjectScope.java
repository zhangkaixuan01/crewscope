package io.crewscope.domain.collaboration;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;

/** WorkProject granularity: the collaboration signals of one project inside a Team. */
public record WorkProjectScope(OrganizationId organizationId, TeamId teamId, WorkProjectId projectId)
    implements CollaborationResourceScope {

  public WorkProjectScope {
    Objects.requireNonNull(organizationId, "organizationId");
    Objects.requireNonNull(teamId, "teamId");
    Objects.requireNonNull(projectId, "projectId");
  }
}
