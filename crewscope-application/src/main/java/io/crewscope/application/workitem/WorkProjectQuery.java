package io.crewscope.application.workitem;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.Optional;

/** Tenant-scoped WorkProject list query with a stable keyset cursor. */
public record WorkProjectQuery(
    OrganizationId organizationId,
    TeamId teamId,
    Optional<WorkProjectCursor> cursor,
    int limit) {

  public WorkProjectQuery {
    organizationId = Objects.requireNonNull(organizationId, "organizationId");
    teamId = Objects.requireNonNull(teamId, "teamId");
    cursor = Objects.requireNonNull(cursor, "cursor");
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100");
    }
  }
}
