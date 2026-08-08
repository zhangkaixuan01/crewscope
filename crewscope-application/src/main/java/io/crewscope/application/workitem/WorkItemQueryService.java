package io.crewscope.application.workitem;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;

/** Serves membership-authorized WorkItem lists and consistent detail snapshots. */
public final class WorkItemQueryService {

  private final WorkItemRepository workItemRepository;
  private final WorkItemCommentRepository commentRepository;
  private final WorkItemResourceLinkRepository resourceLinkRepository;
  private final WorkItemAccessPolicy accessPolicy;
  private final TransactionExecutor transactionExecutor;

  public WorkItemQueryService(
      WorkItemRepository workItemRepository,
      WorkItemCommentRepository commentRepository,
      WorkItemResourceLinkRepository resourceLinkRepository,
      WorkItemAccessPolicy accessPolicy,
      TransactionExecutor transactionExecutor) {
    this.workItemRepository = Objects.requireNonNull(workItemRepository, "workItemRepository");
    this.commentRepository = Objects.requireNonNull(commentRepository, "commentRepository");
    this.resourceLinkRepository =
        Objects.requireNonNull(resourceLinkRepository, "resourceLinkRepository");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
  }

  /** Lists one visible WorkProject using the stable updated-time/ID keyset position. */
  public WorkItemPage list(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      Optional<WorkItemStatus> status,
      Optional<WorkItemCursor> cursor,
      int limit) {
    accessPolicy.requireVisibleProject(context, organizationId, teamId, projectId);
    return workItemRepository.findPage(
        new WorkItemQuery(
            organizationId,
            teamId,
            Optional.of(projectId),
            Objects.requireNonNull(status, "status"),
            Objects.requireNonNull(cursor, "cursor"),
            limit));
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
          return new WorkItemDetails(
              item,
              commentRepository.findByWorkItem(organizationId, item.id()),
              resourceLinkRepository.findByWorkItem(organizationId, item.id()));
        });
  }
}
