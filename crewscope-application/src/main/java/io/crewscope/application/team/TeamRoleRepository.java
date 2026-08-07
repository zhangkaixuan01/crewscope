package io.crewscope.application.team;

import io.crewscope.domain.team.TeamRole;
import java.util.List;

/** Persistence Port for Team-owned role definitions. */
public interface TeamRoleRepository {

    List<TeamRole> createAll(List<TeamRole> roles);
}
