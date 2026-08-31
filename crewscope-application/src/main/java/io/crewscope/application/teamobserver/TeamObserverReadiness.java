package io.crewscope.application.teamobserver;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;

/** Idempotent execution-time readiness boundary for the built-in Team Observer. */
@FunctionalInterface
public interface TeamObserverReadiness {

    TeamObserverInitialization ensureReady(OrganizationId organizationId, TeamId teamId);
}
