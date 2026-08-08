package io.crewscope.application.team;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamStatus;
import io.crewscope.domain.team.UninitializedTeam;
import java.util.Objects;
import java.util.Optional;

/** Stable Team read model that can represent a migrated incomplete foundation. */
public record TeamView(
    TeamId id,
    OrganizationId organizationId,
    String name,
    TeamStatus status,
    TeamInitializationStatus initializationStatus,
    Optional<TeamMemberId> ownerMemberId,
    Optional<WorkspaceId> defaultWorkspaceId,
    long version) {

  public TeamView {
    id = Objects.requireNonNull(id, "id");
    organizationId = Objects.requireNonNull(organizationId, "organizationId");
    name = Objects.requireNonNull(name, "name");
    status = Objects.requireNonNull(status, "status");
    initializationStatus = Objects.requireNonNull(initializationStatus, "initializationStatus");
    ownerMemberId = Objects.requireNonNull(ownerMemberId, "ownerMemberId");
    defaultWorkspaceId = Objects.requireNonNull(defaultWorkspaceId, "defaultWorkspaceId");
    boolean ready = initializationStatus == TeamInitializationStatus.READY;
    if (ready != (ownerMemberId.isPresent() && defaultWorkspaceId.isPresent())) {
      throw new IllegalArgumentException("Team initialization references are inconsistent");
    }
  }

  public static TeamView from(Team team) {
    Team source = Objects.requireNonNull(team, "team");
    return new TeamView(
        source.id(),
        source.organizationId(),
        source.name(),
        source.status(),
        TeamInitializationStatus.READY,
        Optional.of(source.ownerMemberId()),
        Optional.of(source.defaultWorkspaceId()),
        source.version());
  }

  public static TeamView from(UninitializedTeam team) {
    UninitializedTeam source = Objects.requireNonNull(team, "team");
    return new TeamView(
        source.id(),
        source.organizationId(),
        source.name(),
        source.status(),
        TeamInitializationStatus.INITIALIZATION_REQUIRED,
        Optional.empty(),
        Optional.empty(),
        source.version());
  }
}
