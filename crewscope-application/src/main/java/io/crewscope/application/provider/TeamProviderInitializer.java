package io.crewscope.application.provider;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.workspace.Workspace;

/** Extension point that closes product-owned Provider facts during Team foundation creation. */
@FunctionalInterface
public interface TeamProviderInitializer {

  void initialize(Team team, Workspace defaultWorkspace, Principal actor);
}
