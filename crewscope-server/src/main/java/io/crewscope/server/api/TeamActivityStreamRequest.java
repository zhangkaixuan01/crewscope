package io.crewscope.server.api;

import io.crewscope.application.activity.ActivityFilter;
import io.crewscope.application.activity.TeamActivitySnapshotRequest;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** Stable route and normalized filter captured for the lifetime of one Team SSE connection. */
public record TeamActivityStreamRequest(
    OrganizationId organizationId,
    TeamId teamId,
    ProjectionName projectionName,
    ActivityFilter filter) {

  public TeamActivityStreamRequest {
    organizationId = Objects.requireNonNull(organizationId, "organizationId");
    teamId = Objects.requireNonNull(teamId, "teamId");
    projectionName = Objects.requireNonNull(projectionName, "projectionName");
    filter = Objects.requireNonNull(filter, "filter");
  }

  TeamActivitySnapshotRequest snapshotRequest(int limit) {
    return new TeamActivitySnapshotRequest(
        organizationId, teamId, projectionName, filter, limit);
  }
}
