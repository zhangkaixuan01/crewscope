package io.crewscope.application.workdesk;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;

/** Member-authorized facade for the derived personal WorkDesk projection. */
public final class WorkDeskQueryService {
  private final WorkDeskRepository repository;
  private final WorkDeskAccessPolicy accessPolicy;

  public WorkDeskQueryService(WorkDeskRepository repository, WorkDeskAccessPolicy accessPolicy) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
  }

  public WorkDeskSummary summarize(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      Optional<WorkProjectId> projectId,
      Optional<ResponsibilityRole> responsibilityRole,
      boolean onlyNeedsAction) {
    var member = accessPolicy.requireMember(context, organizationId, teamId);
    return repository.summarize(new WorkDeskQuery(
        organizationId, teamId, member.id(), member.userPrincipalId(),
        projectId, responsibilityRole, onlyNeedsAction));
  }
}
