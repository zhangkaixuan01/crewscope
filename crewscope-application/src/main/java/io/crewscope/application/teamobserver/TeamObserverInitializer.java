package io.crewscope.application.teamobserver;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.Workspace;

/** Idempotently creates the built-in Observer identity that belongs to one complete Team. */
@FunctionalInterface
public interface TeamObserverInitializer {

    void initialize(Team team, Workspace workspace, TeamMember ownerMember, Principal ownerUser);
}
