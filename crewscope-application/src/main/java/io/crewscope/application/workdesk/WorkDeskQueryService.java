package io.crewscope.application.workdesk;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemAvailableTransition;
import io.crewscope.application.workitem.WorkItemTransitionAvailabilityProjector;
import io.crewscope.application.workitem.WorkItemTransitionPermissionResolver;
import io.crewscope.application.workitem.WorkItemTransitionSubject;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Member-authorized facade for the derived personal WorkDesk projection. */
public final class WorkDeskQueryService {
  private final WorkDeskRepository repository;
  private final WorkDeskAccessPolicy accessPolicy;
  private final WorkItemAccessPolicy workItemAccessPolicy;
  private final WorkItemTransitionAvailabilityProjector transitions;
  private final TimeProvider timeProvider;

  public WorkDeskQueryService(
      WorkDeskRepository repository,
      WorkDeskAccessPolicy accessPolicy,
      WorkItemAccessPolicy workItemAccessPolicy,
      WorkItemTransitionAvailabilityProjector transitions,
      TimeProvider timeProvider) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.workItemAccessPolicy = Objects.requireNonNull(workItemAccessPolicy, "workItemAccessPolicy");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  public WorkDeskSummary summarize(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      Optional<WorkProjectId> projectId,
      Optional<ResponsibilityRole> responsibilityRole,
      boolean onlyNeedsAction) {
    var member = accessPolicy.requireMember(context, organizationId, teamId);
    WorkDeskSummary summary = repository.summarize(new WorkDeskQuery(
        organizationId, teamId, member.id(), member.userPrincipalId(),
        projectId, responsibilityRole, onlyNeedsAction));
    return withAvailability(summary, context, organizationId, teamId);
  }

  /**
   * Fills each WorkItem row's executable transitions.
   *
   * <p>The member's roles and grants are read once for the whole page and reused for every row and
   * every project on it — a per-row policy call would re-read the Team, the membership, the project
   * and both grant tables per item, which is exactly the N+1 the WorkDesk budget cannot afford.
   *
   * <p>Rows without a {@link WorkItemTransitionSubject} keep the empty list the adapter gave them:
   * they are review requests, executions or inbox pointers, and none of them has WorkItem edges.
   */
  private WorkDeskSummary withAvailability(
      WorkDeskSummary summary,
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId) {
    WorkItemTransitionPermissionResolver resolver = workItemAccessPolicy.resolvePermission(
        context, organizationId, teamId, timeProvider.now());
    List<WorkDeskSection> sections = summary.sections().stream()
        .map(section -> new WorkDeskSection(
            section.key(), section.title(), section.priority(), section.total(), section.truncated(),
            section.items().stream().map(item -> withActions(item, resolver)).toList()))
        .toList();
    return new WorkDeskSummary(
        summary.organizationId(), summary.teamId(), summary.projectId(), summary.generatedAt(),
        sections);
  }

  private WorkDeskItem withActions(
      WorkDeskItem item, WorkItemTransitionPermissionResolver resolver) {
    Optional<WorkItemTransitionSubject> subject = item.transitionSubject();
    if (subject.isEmpty()) {
      return item;
    }
    WorkItemTransitionSubject value = subject.orElseThrow();
    List<WorkItemAvailableTransition> actions = transitions.enabled(
        value.status(),
        value.nativeSource(),
        resolver.granted(value.projectId(), TeamPermission.WORK_PARTICIPATE));
    return new WorkDeskItem(
        item.objectType(), item.objectId(), item.projectId(), item.title(), item.status(),
        item.updatedAt(), item.responsibilityRole(), item.needsAction(), item.urgency(),
        item.progress(), actions, subject, item.route());
  }
}
