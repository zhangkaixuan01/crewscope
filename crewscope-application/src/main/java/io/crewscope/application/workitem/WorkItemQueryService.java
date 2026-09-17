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
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Serves membership-authorized WorkItem lists and consistent detail snapshots. */
public final class WorkItemQueryService {

  private final WorkItemRepository workItemRepository;
  private final WorkItemCommentRepository commentRepository;
  private final WorkItemResourceLinkRepository resourceLinkRepository;
  private final WorkItemAccessPolicy accessPolicy;
  private final WorkItemTransitionAvailabilityProjector transitions;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public WorkItemQueryService(
      WorkItemRepository workItemRepository,
      WorkItemCommentRepository commentRepository,
      WorkItemResourceLinkRepository resourceLinkRepository,
      WorkItemAccessPolicy accessPolicy,
      WorkItemTransitionAvailabilityProjector transitions,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.workItemRepository = Objects.requireNonNull(workItemRepository, "workItemRepository");
    this.commentRepository = Objects.requireNonNull(commentRepository, "commentRepository");
    this.resourceLinkRepository =
        Objects.requireNonNull(resourceLinkRepository, "resourceLinkRepository");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  /**
   * Lists one visible WorkProject using the stable updated-time/ID keyset position.
   *
   * <p>Every row carries the full adjudicated edge list, so a list row or board card can offer the
   * same actions as the detail panel without a per-row round trip.
   *
   * <p>The member's roles and grants are read <em>once</em> for the whole page, and the collection is
   * scoped to a single WorkProject, so the one verdict is reused for every row. Calling
   * {@link WorkItemAccessPolicy#hasPermission} per item would re-read the Team, the membership, the
   * project and both grant tables for each row — the N+1 that {@link
   * WorkItemTransitionPermissionResolver} exists to avoid.
   */
  public WorkItemListPage list(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      Optional<WorkItemStatus> status,
      Optional<WorkItemCursor> cursor,
      int limit) {
    accessPolicy.requireVisibleProject(context, organizationId, teamId, projectId);
    WorkItemPage page =
        workItemRepository.findPage(
            new WorkItemQuery(
                organizationId,
                teamId,
                Optional.of(projectId),
                Objects.requireNonNull(status, "status"),
                Objects.requireNonNull(cursor, "cursor"),
                limit));
    UtcTimestamp now = timeProvider.now();
    WorkItemTransitionPermissionResolver resolver =
        accessPolicy.resolvePermission(context, organizationId, teamId, now);
    boolean participates = resolver.granted(projectId, TeamPermission.WORK_PARTICIPATE);
    List<WorkItemListRow> rows =
        page.items().stream()
            .map(
                item ->
                    new WorkItemListRow(
                        item, transitions.all(item.status(), item.source().isNative(), participates)))
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
          return new WorkItemDetails(
              item,
              commentRepository.findByWorkItem(organizationId, item.id()),
              resourceLinkRepository.findByWorkItem(organizationId, item.id()),
              transitions.all(item.status(), item.source().isNative(), participates));
        });
  }
}
