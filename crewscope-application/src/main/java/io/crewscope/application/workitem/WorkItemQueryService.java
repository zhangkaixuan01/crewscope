package io.crewscope.application.workitem;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Serves membership-authorized WorkItem lists and consistent detail snapshots. */
public final class WorkItemQueryService {

  private final WorkItemRepository workItemRepository;
  private final WorkItemCommentRepository commentRepository;
  private final WorkItemResourceLinkRepository resourceLinkRepository;
  private final WorkItemAccessPolicy accessPolicy;
  private final WorkItemTransitionAvailabilityProjector transitions;
  private final WorkItemSummaryRepository summaryRepository;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public WorkItemQueryService(
      WorkItemRepository workItemRepository,
      WorkItemCommentRepository commentRepository,
      WorkItemResourceLinkRepository resourceLinkRepository,
      WorkItemAccessPolicy accessPolicy,
      WorkItemTransitionAvailabilityProjector transitions,
      WorkItemSummaryRepository summaryRepository,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.workItemRepository = Objects.requireNonNull(workItemRepository, "workItemRepository");
    this.commentRepository = Objects.requireNonNull(commentRepository, "commentRepository");
    this.resourceLinkRepository =
        Objects.requireNonNull(resourceLinkRepository, "resourceLinkRepository");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.summaryRepository = Objects.requireNonNull(summaryRepository, "summaryRepository");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  /**
   * Lists one visible WorkProject with the server-owned ordering, multi-value filter and stable
   * keyset position (M9b-A06).
   *
   * <p>Every row carries the full adjudicated edge list and the execution/todo summary, so a list row
   * or board card can offer the same actions and the same runtime facts as the detail panel without a
   * per-row round trip.
   *
   * <p>The member's roles and grants are read <em>once</em> for the whole page, and the collection is
   * scoped to a single WorkProject, so the one verdict is reused for every row. Calling
   * {@link WorkItemAccessPolicy#hasPermission} per item would re-read the Team, the membership, the
   * project and both grant tables for each row — the N+1 that {@link
   * WorkItemTransitionPermissionResolver} exists to avoid. The summary is likewise assembled in one
   * batch over the page's ID set, never row by row.
   */
  public WorkItemListPage list(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemFilter filter,
      WorkItemSort sort,
      Optional<WorkItemCursor> cursor,
      int limit) {
    accessPolicy.requireVisibleProject(context, organizationId, teamId, projectId);
    WorkItemQuery query =
        WorkItemQuery.create(
            organizationId,
            teamId,
            projectId,
            context.actor().id(),
            Objects.requireNonNull(filter, "filter"),
            Objects.requireNonNull(sort, "sort"),
            Objects.requireNonNull(cursor, "cursor"),
            limit);
    WorkItemPage page = workItemRepository.findPage(query);
    UtcTimestamp now = timeProvider.now();
    WorkItemTransitionPermissionResolver resolver =
        accessPolicy.resolvePermission(context, organizationId, teamId, now);
    boolean participates = resolver.granted(projectId, TeamPermission.WORK_PARTICIPATE);
    Map<WorkItemId, WorkItemExecutionSummary> summaries =
        summaryRepository.summarize(
            organizationId, teamId, Optional.of(projectId),
            page.items().stream().map(WorkItem::id).toList(), now);
    List<WorkItemListRow> rows =
        page.items().stream()
            .map(
                item ->
                    new WorkItemListRow(
                        item,
                        transitions.all(item.status(), item.source().isNative(), participates),
                        Optional.ofNullable(summaries.get(item.id()))))
            .toList();
    return new WorkItemListPage(rows, page.nextCursor());
  }

  /** Reads the aggregate and immutable children in one transactionally consistent snapshot. */
  public WorkItemDetails get(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId) {
    return transactionExecutor.required(
        () -> {
          WorkItem item =
              accessPolicy.requireVisibleWorkItem(
                  context, organizationId, teamId, projectId, workItemId);
          boolean participates =
              accessPolicy.hasPermission(
                  context,
                  organizationId,
                  teamId,
                  projectId,
                  TeamPermission.WORK_PARTICIPATE,
                  timeProvider.now());
          UtcTimestamp now = timeProvider.now();
          Map<WorkItemId, WorkItemExecutionSummary> summaries =
              summaryRepository.summarize(
                  organizationId, teamId, Optional.of(projectId), List.of(workItemId), now);
          return new WorkItemDetails(
              item,
              commentRepository.findByWorkItem(organizationId, item.id()),
              resourceLinkRepository.findByWorkItem(organizationId, item.id()),
              transitions.all(item.status(), item.source().isNative(), participates),
              Optional.ofNullable(
                  summaries.getOrDefault(
                      item.id(),
                      WorkItemExecutionSummary.none(
                          item.id(), item.version(), item.status(), now))));
        });
  }
}
