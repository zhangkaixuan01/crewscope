package io.crewscope.application.workdesk;

import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;

/** Fully scoped, read-only query for the current member's personal work desk. */
public record WorkDeskQuery(
    OrganizationId organizationId,
    TeamId teamId,
    TeamMemberId memberId,
    PrincipalId principalId,
    Optional<WorkProjectId> projectId,
    Optional<ResponsibilityRole> responsibilityRole,
    boolean onlyNeedsAction) {

  public WorkDeskQuery {
    organizationId = Objects.requireNonNull(organizationId, "organizationId");
    teamId = Objects.requireNonNull(teamId, "teamId");
    memberId = Objects.requireNonNull(memberId, "memberId");
    principalId = Objects.requireNonNull(principalId, "principalId");
    projectId = Objects.requireNonNull(projectId, "projectId");
    responsibilityRole = Objects.requireNonNull(responsibilityRole, "responsibilityRole");
  }
}
