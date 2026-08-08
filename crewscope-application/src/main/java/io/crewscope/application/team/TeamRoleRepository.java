package io.crewscope.application.team;

import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamRoleId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for Team-owned role definitions. */
public interface TeamRoleRepository {

    List<TeamRole> createAll(List<TeamRole> roles);

    /** Commits one role-definition lifecycle change with an optimistic version predicate. */
    default TeamRole update(TeamRole role) {
        throw new UnsupportedOperationException("TeamRole update is not implemented");
    }

    default Optional<TeamRole> findById(OrganizationId organizationId, TeamRoleId id) {
        throw new UnsupportedOperationException("TeamRole lookup is not implemented");
    }

    default List<TeamRole> findByTeam(OrganizationId organizationId, TeamId teamId) {
        throw new UnsupportedOperationException("TeamRole list is not implemented");
    }
}
