package io.crewscope.application.team;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.UninitializedTeam;
import java.util.List;
import java.util.Optional;

/** Persistence Port for Team aggregate roots. */
public interface TeamRepository {

  Team create(Team team);

  /** Commits one domain transition with an optimistic version predicate. */
  default Team update(Team team) {
    throw new UnsupportedOperationException("Team update is not implemented");
  }

  /** Finds one Team only inside the explicit Organization boundary. */
  default Optional<Team> findById(OrganizationId organizationId, TeamId id) {
    throw new UnsupportedOperationException("Team lookup is not implemented");
  }

  /** Locks one complete Team as the serialization point for membership mutations. */
  default Optional<Team> lockById(OrganizationId organizationId, TeamId id) {
    throw new UnsupportedOperationException("Team lock is not implemented");
  }

  /** Lists initialized Teams in which the USER has a current active Membership. */
  default List<Team> findActiveByMember(
      OrganizationId organizationId, PrincipalId userPrincipalId) {
    throw new UnsupportedOperationException("Team membership list is not implemented");
  }

  /** Reads a migrated incomplete Team without passing it through the complete aggregate Mapper. */
  default Optional<UninitializedTeam> findUninitializedById(
      OrganizationId organizationId, TeamId id) {
    throw new UnsupportedOperationException("Uninitialized Team lookup is not implemented");
  }

  /** Locks and returns an incomplete Team as the serialization point for one-time completion. */
  default Optional<UninitializedTeam> lockUninitializedById(
      OrganizationId organizationId, TeamId id) {
    throw new UnsupportedOperationException("Uninitialized Team lock is not implemented");
  }
}
