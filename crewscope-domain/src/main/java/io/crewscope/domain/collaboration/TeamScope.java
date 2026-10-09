package io.crewscope.domain.collaboration;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** Team-wide granularity: every collaboration signal of one Team. */
public record TeamScope(OrganizationId organizationId, TeamId teamId)
    implements CollaborationResourceScope {

  public TeamScope {
    Objects.requireNonNull(organizationId, "organizationId");
    Objects.requireNonNull(teamId, "teamId");
  }
}
