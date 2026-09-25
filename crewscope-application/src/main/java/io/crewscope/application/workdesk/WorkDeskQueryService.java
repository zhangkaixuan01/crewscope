package io.crewscope.application.workdesk;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemAvailableTransition;
import io.crewscope.application.workitem.WorkItemBlockedReason;
import io.crewscope.application.workitem.WorkItemExecutionSummary;
import io.crewscope.application.workitem.WorkItemSummaryRepository;
import io.crewscope.application.workitem.WorkItemTransitionAvailabilityProjector;
import io.crewscope.application.workitem.WorkItemTransitionPermissionResolver;
import io.crewscope.application.workitem.WorkItemTransitionSubject;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Member-authorized facade for the derived personal WorkDesk projection. */
public final class WorkDeskQueryService {
  private final WorkDeskRepository repository;
  private final WorkItemSummaryRepository summaryRepository;
  private final WorkDeskAccessPolicy accessPolicy;
  private final WorkItemAccessPolicy workItemAccessPolicy;
  private final WorkItemTransitionAvailabilityProjector transitions;
  private final TimeProvider timeProvider;

  public WorkDeskQueryService(
      WorkDeskRepository repository,
      WorkItemSummaryRepository summaryRepository,
      WorkDeskAccessPolicy accessPolicy,
      WorkItemAccessPolicy workItemAccessPolicy,
      WorkItemTransitionAvailabilityProjector transitions,
      TimeProvider timeProvider) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.summaryRepository = Objects.requireNonNull(summaryRepository, "summaryRepository");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.workItemAccessPolicy = Objects.requireNonNull(workItemAccessPolicy, "workItemAccessPolicy");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  /** The first screen with the default per-section page size. */
  public WorkDeskSummary summarize(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      Optional<WorkProjectId> projectId,
      Optional<ResponsibilityRole> responsibilityRole,
      boolean onlyNeedsAction) {
    return summarize(
        context, organizationId, teamId, projectId, responsibilityRole, onlyNeedsAction,
        WorkDeskQuery.DEFAULT_SECTION_LIMIT);
  }

  /**
   * The first screen with the caller's per-section page size (M9b-A06).
   *
   * <p>Every section is truncated to {@code sectionLimit} rows with its true full-set total and a
   * continuation position when more rows follow; row facts are layered on afterwards.
   */
  public WorkDeskSummary summarize(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      Optional<WorkProjectId> projectId,
      Optional<ResponsibilityRole> responsibilityRole,
      boolean onlyNeedsAction,
      int sectionLimit) {
    WorkDeskQuery query = queryFor(
        context, organizationId, teamId, projectId, responsibilityRole, onlyNeedsAction,
        sectionLimit);
    return withAvailability(withRowFacts(repository.summarize(query), query),
        context, organizationId, teamId);
  }

  /**
   * Continues exactly one section from its signed keyset position (M9b-A06).
   *
   * <p>The continuation runs only the continued section's query and returns its next page with the
   * same shape as the first screen: true full-set total, next position exactly when more rows
   * follow, row facts layered on.
   */
  public WorkDeskSection summarizeSection(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      Optional<WorkProjectId> projectId,
      Optional<ResponsibilityRole> responsibilityRole,
      boolean onlyNeedsAction,
      int sectionLimit,
      String sectionKey,
      WorkDeskSectionPosition position) {
    Objects.requireNonNull(position, "position");
    if (!position.sectionKey().equals(sectionKey)) {
      throw new IllegalArgumentException("the cursor continues a different section");
    }
    WorkDeskQuery query = queryFor(
        context, organizationId, teamId, projectId, responsibilityRole, onlyNeedsAction,
        sectionLimit);
    WorkDeskSection section = repository.summarizeSection(query, position);
    if (!section.key().equals(sectionKey)) {
      throw new IllegalStateException("the repository answered a different section");
    }
    return withSectionAvailability(withRowFacts(section, query), context, organizationId, teamId);
  }

  private WorkDeskSection withSectionAvailability(
      WorkDeskSection section,
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId) {
    WorkItemTransitionPermissionResolver resolver = workItemAccessPolicy.resolvePermission(
        context, organizationId, teamId, timeProvider.now());
    return withAvailability(section, resolver);
  }

  private WorkDeskQuery queryFor(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      Optional<WorkProjectId> projectId,
      Optional<ResponsibilityRole> responsibilityRole,
      boolean onlyNeedsAction,
      int sectionLimit) {
    var member = accessPolicy.requireMember(context, organizationId, teamId);
    return new WorkDeskQuery(
        organizationId, teamId, member.id(), member.userPrincipalId(),
        projectId, responsibilityRole, onlyNeedsAction, sectionLimit);
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
        .map(section -> withAvailability(section, resolver))
        .toList();
    return new WorkDeskSummary(
        summary.organizationId(), summary.teamId(), summary.projectId(), summary.generatedAt(),
        sections);
  }

  private WorkDeskSection withAvailability(
      WorkDeskSection section, WorkItemTransitionPermissionResolver resolver) {
    return new WorkDeskSection(
        section.key(), section.title(), section.priority(), section.total(), section.truncated(),
        section.items().stream().map(item -> withActions(item, resolver)).toList(),
        section.nextPosition());
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
    return item.withActions(actions, subject);
  }

  /**
   * Layers the shared execution/todo read model onto the WorkItem-backed rows of one answer.
   *
   * <p>All rows of the whole first screen — or the one continued section — are assembled in a
   * single batch keyed by the distinct WorkItem IDs on the page, so a six-section desk costs one
   * summary call, never one per row or per section. {@code waitingOn} names the principal of the
   * first blocked reason that carries one; its display name stays empty for the surface to resolve
   * from its own member list.
   */
  private WorkDeskSummary withRowFacts(WorkDeskSummary summary, WorkDeskQuery query) {
    Map<String, WorkItemId> wanted = new LinkedHashMap<>();
    for (WorkDeskSection section : summary.sections()) {
      collectWorkItems(section, wanted);
    }
    Map<WorkItemId, WorkItemExecutionSummary> summaries = assemble(wanted, query);
    List<WorkDeskSection> sections =
        summary.sections().stream().map(section -> layer(section, summaries)).toList();
    return new WorkDeskSummary(
        summary.organizationId(), summary.teamId(), summary.projectId(), summary.generatedAt(),
        sections);
  }

  private WorkDeskSection withRowFacts(WorkDeskSection section, WorkDeskQuery query) {
    Map<String, WorkItemId> wanted = new LinkedHashMap<>();
    collectWorkItems(section, wanted);
    return layer(section, assemble(wanted, query));
  }

  private static void collectWorkItems(
      WorkDeskSection section, Map<String, WorkItemId> wanted) {
    for (WorkDeskItem item : section.items()) {
      item.workItemId().ifPresent(id -> wanted.putIfAbsent(id, WorkItemId.from(id)));
    }
  }

  private Map<WorkItemId, WorkItemExecutionSummary> assemble(
      Map<String, WorkItemId> wanted, WorkDeskQuery query) {
    if (wanted.isEmpty()) {
      return Map.of();
    }
    return summaryRepository.summarize(
        query.organizationId(), query.teamId(), query.projectId(), wanted.values(),
        timeProvider.now());
  }

  private WorkDeskSection layer(
      WorkDeskSection section, Map<WorkItemId, WorkItemExecutionSummary> summaries) {
    if (section.items().stream().noneMatch(item -> item.workItemId().isPresent())) {
      return section;
    }
    return new WorkDeskSection(
        section.key(), section.title(), section.priority(), section.total(), section.truncated(),
        section.items().stream()
            .map(item -> item.workItemId()
                .map(WorkItemId::from)
                .map(id -> item.withRowFacts(
                    Optional.ofNullable(summaries.get(id)),
                    summaries.containsKey(id)
                        ? waitingOnFrom(summaries.get(id))
                        : Optional.empty()))
                .orElse(item))
            .toList(),
        section.nextPosition());
  }

  private static Optional<WorkDeskWaitingOn> waitingOnFrom(WorkItemExecutionSummary summary) {
    return summary.blockedReasons().stream()
        .map(WorkItemBlockedReason::waitingOnPrincipalId)
        .flatMap(Optional::stream)
        .findFirst()
        .map(principal -> new WorkDeskWaitingOn(
            principal.toString(), Optional.empty(), Optional.empty()));
  }
}
