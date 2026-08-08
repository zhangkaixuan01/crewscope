package io.crewscope.domain.team.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.team.TeamInitialization;
import java.util.Objects;
import java.util.UUID;

/** Version 1 fact emitted when a migrated Team receives its accountable foundation. */
public record TeamInitializationCompleted(
    UUID ownerPrincipalId, UUID ownerMemberId, UUID defaultWorkspaceId) implements DomainEvent {

  public TeamInitializationCompleted {
    ownerPrincipalId = Objects.requireNonNull(ownerPrincipalId, "ownerPrincipalId");
    ownerMemberId = Objects.requireNonNull(ownerMemberId, "ownerMemberId");
    defaultWorkspaceId = Objects.requireNonNull(defaultWorkspaceId, "defaultWorkspaceId");
  }

  public static TeamInitializationCompleted from(TeamInitialization initialization) {
    TeamInitialization source = Objects.requireNonNull(initialization, "initialization");
    return new TeamInitializationCompleted(
        source.ownerMember().userPrincipalId().value(),
        source.ownerMember().id().value(),
        source.defaultWorkspace().id().value());
  }
}
