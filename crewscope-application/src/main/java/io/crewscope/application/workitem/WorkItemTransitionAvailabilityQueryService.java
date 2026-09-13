package io.crewscope.application.workitem;

import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemTransitionCatalog;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Computes discoverability from the same WorkItem state machine and permission policy as commands. */
public final class WorkItemTransitionAvailabilityQueryService {
  private final WorkItemAccessPolicy accessPolicy;
  private final TimeProvider timeProvider;

  public WorkItemTransitionAvailabilityQueryService(
      WorkItemAccessPolicy accessPolicy, TimeProvider timeProvider) {
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  public List<WorkItemAvailableTransition> list(WorkItemTransitionAvailabilityQuery query) {
    WorkItemTransitionAvailabilityQuery value = Objects.requireNonNull(query, "query");
    WorkItem item = accessPolicy.requireVisibleWorkItem(
        value.context(), value.organizationId(), value.teamId(), value.projectId(), value.workItemId());
    UtcTimestamp now = timeProvider.now();
    boolean permission = accessPolicy.hasPermission(
        value.context(), value.organizationId(), value.teamId(), value.projectId(),
        TeamPermission.WORK_PARTICIPATE, now);
    return WorkItemTransitionCatalog.from(item.status()).stream()
        .map(edge -> evaluate(item, edge, permission))
        .toList();
  }

  private static WorkItemAvailableTransition evaluate(
      WorkItem item, WorkItemTransitionCatalog.Edge edge, boolean permission) {
    if (item.status() == WorkItemStatus.ARCHIVED) {
      return WorkItemAvailableTransition.disabled(
          edge, WorkItemTransitionBlockReason.ARCHIVED, Optional.empty());
    }
    if (!item.source().isNative()) {
      return WorkItemAvailableTransition.disabled(
          edge, WorkItemTransitionBlockReason.EXTERNAL_PROVIDER_MANAGED, Optional.empty());
    }
    if (!permission) {
      return WorkItemAvailableTransition.disabled(
          edge, WorkItemTransitionBlockReason.PERMISSION_DENIED, Optional.empty());
    }
    return WorkItemAvailableTransition.enabled(edge);
  }
}
