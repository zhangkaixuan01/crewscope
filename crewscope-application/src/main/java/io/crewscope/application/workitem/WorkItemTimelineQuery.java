package io.crewscope.application.workitem;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Tenant-scoped WorkItem event query passed to the timeline persistence Port. */
public record WorkItemTimelineQuery(
    OrganizationId organizationId,
    TeamId teamId,
    WorkspaceId workspaceId,
    WorkItemId workItemId,
    Set<String> visibleEventTypes,
    Optional<WorkItemTimelineCursor> cursor,
    int limit) {

  public static final int MAX_LIMIT = 100;

  public WorkItemTimelineQuery {
    organizationId = Objects.requireNonNull(organizationId, "organizationId");
    teamId = Objects.requireNonNull(teamId, "teamId");
    workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    workItemId = Objects.requireNonNull(workItemId, "workItemId");
    visibleEventTypes = Set.copyOf(Objects.requireNonNull(visibleEventTypes, "visibleEventTypes"));
    if (visibleEventTypes.isEmpty()
        || visibleEventTypes.stream().anyMatch(type -> type == null || type.isBlank())) {
      throw new IllegalArgumentException("visibleEventTypes must contain non-blank values");
    }
    cursor = Objects.requireNonNull(cursor, "cursor");
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
    }
  }
}
