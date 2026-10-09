package io.crewscope.domain.collaboration;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.UUID;

/** Single-resource granularity: one WorkItem or Conversation. */
public record ResourceScope(
    OrganizationId organizationId, TeamId teamId, CollaborationResourceType type, UUID resourceId)
    implements CollaborationResourceScope {

  public ResourceScope {
    Objects.requireNonNull(organizationId, "organizationId");
    Objects.requireNonNull(teamId, "teamId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(resourceId, "resourceId");
  }
}
