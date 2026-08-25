package io.crewscope.application.teamobserver;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;
import java.util.Optional;

/** Atomic persistence Port for each Team's unique built-in Observer Principal/Profile pair. */
public interface DefaultTeamObserverRepository {

    /**
     * Inserts the deterministic candidate when absent and otherwise returns the existing pair.
     * Implementations serialize by Organization and Team and never commit a partial pair.
     */
    TeamObserverInitialization initializeIfAbsent(TeamObserverInitialization candidate);

    Optional<TeamObserverInitialization> findByTeam(
            OrganizationId organizationId, TeamId teamId);

    /** Commits synchronized Principal/Profile lifecycle changes atomically with strong versions. */
    TeamObserverInitialization updateLifecycle(TeamObserverInitialization initialization);
}
