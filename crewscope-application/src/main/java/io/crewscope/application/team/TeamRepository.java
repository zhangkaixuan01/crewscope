package io.crewscope.application.team;

import io.crewscope.domain.team.Team;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
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
}
