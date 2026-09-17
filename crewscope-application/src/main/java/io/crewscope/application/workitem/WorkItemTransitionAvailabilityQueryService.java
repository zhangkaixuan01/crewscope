package io.crewscope.application.workitem;

import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.workitem.WorkItem;
import java.util.List;
import java.util.Objects;

/** Computes discoverability from the same WorkItem state machine and permission policy as commands. */
public final class WorkItemTransitionAvailabilityQueryService {
  private final WorkItemAccessPolicy accessPolicy;
  private final WorkItemTransitionAvailabilityProjector projector;
  private final TimeProvider timeProvider;

  public WorkItemTransitionAvailabilityQueryService(
      WorkItemAccessPolicy accessPolicy,
      WorkItemTransitionAvailabilityProjector projector,
      TimeProvider timeProvider) {
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.projector = Objects.requireNonNull(projector, "projector");
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
    return projector.all(item.status(), item.source().isNative(), permission);
  }
}
