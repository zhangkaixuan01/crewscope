package io.crewscope.application.team;

import io.crewscope.domain.team.Team;

/** Persistence Port for Team aggregate roots. */
public interface TeamRepository {

    Team create(Team team);
}
