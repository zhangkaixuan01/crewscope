package io.crewscope.application.workitem;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Serves the membership-authorized M1 WorkItem business timeline. */
public final class WorkItemTimelineService {

  private static final Set<String> M1_VISIBLE_EVENT_TYPES =
      Set.of(
          "WORK_ITEM_CREATED",
          "WORK_ITEM_STATUS_CHANGED",
          "WORK_ITEM_COMMENT_ADDED",
          "WORK_ITEM_RESOURCE_LINKED",
          "WORK_ITEM_OWNER_ASSIGNED",
          "WORK_ITEM_OWNER_REPLACED",
          "WORK_ITEM_EXECUTOR_ASSIGNED",
          "WORK_ITEM_GATE_REVIEWER_ASSIGNED",
          "WORK_ITEM_ADVISORY_REVIEWER_ASSIGNED",
          "WORK_ITEM_RESPONSIBILITY_RELEASED");

  private final WorkItemTimelineRepository timelineRepository;
  private final WorkItemAccessPolicy accessPolicy;
  private final TransactionExecutor transactionExecutor;

  public WorkItemTimelineService(
      WorkItemTimelineRepository timelineRepository,
      WorkItemAccessPolicy accessPolicy,
      TransactionExecutor transactionExecutor) {
    this.timelineRepository = Objects.requireNonNull(timelineRepository, "timelineRepository");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
  }

  /** Reads one page only after the complete URL and persisted WorkItem scope is authorized. */
  public WorkItemTimelinePage list(
      TeamAccessContext context,
      OrganizationId organizationId,
      TeamId teamId,
      WorkProjectId projectId,
      WorkItemId workItemId,
      Optional<WorkItemTimelineCursor> cursor,
      int limit) {
    return transactionExecutor.required(
        () -> {
          WorkItem item =
              accessPolicy.requireVisibleWorkItem(
                  context, organizationId, teamId, projectId, workItemId);
          return timelineRepository.findPage(
              new WorkItemTimelineQuery(
                  organizationId,
                  teamId,
                  item.scope().workspaceId(),
                  item.id(),
                  M1_VISIBLE_EVENT_TYPES,
                  Objects.requireNonNull(cursor, "cursor"),
                  limit));
        });
  }
}
