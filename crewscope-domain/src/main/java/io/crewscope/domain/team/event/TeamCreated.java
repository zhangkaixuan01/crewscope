package io.crewscope.domain.team.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.team.TeamInitialization;
import java.util.Objects;
import java.util.UUID;

/** Version 1 fact emitted after a complete Team foundation is created. */
public record TeamCreated(
    String name, UUID ownerMemberId, UUID defaultWorkspaceId, UUID ownerAgentProfileId)
    implements DomainEvent {

  public TeamCreated {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    name = name.strip();
    ownerMemberId = Objects.requireNonNull(ownerMemberId, "ownerMemberId");
    defaultWorkspaceId = Objects.requireNonNull(defaultWorkspaceId, "defaultWorkspaceId");
    ownerAgentProfileId = Objects.requireNonNull(ownerAgentProfileId, "ownerAgentProfileId");
  }

  public static TeamCreated from(TeamInitialization initialization) {
    TeamInitialization source = Objects.requireNonNull(initialization, "initialization");
    return new TeamCreated(
        source.team().name(),
        source.ownerMember().id().value(),
        source.defaultWorkspace().id().value(),
        source.ownerPersonalAgent().agentProfile().id().value());
  }
}
