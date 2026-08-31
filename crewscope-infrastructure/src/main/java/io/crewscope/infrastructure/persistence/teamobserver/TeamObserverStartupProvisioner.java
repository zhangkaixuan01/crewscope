package io.crewscope.infrastructure.persistence.teamobserver;

import io.crewscope.application.teamobserver.TeamObserverProvisioningService;
import io.crewscope.domain.agent.AgentModelPreflightException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Repairs Observer lifecycle for Teams created after the historical V28 backfill. */
@Component
public final class TeamObserverStartupProvisioner {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TeamObserverStartupProvisioner.class);

    private final JdbcTemplate jdbc;
    private final TeamObserverProvisioningService provisioning;

    public TeamObserverStartupProvisioner(
            JdbcTemplate jdbc, TeamObserverProvisioningService provisioning) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.provisioning = Objects.requireNonNull(provisioning, "provisioning");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void repairExistingTeams() {
        List<TeamCoordinate> teams = jdbc.query(
                """
                SELECT organization_id, id
                  FROM crewscope.team
                 WHERE status = 'ACTIVE'
                   AND owner_member_id IS NOT NULL
                   AND default_workspace_id IS NOT NULL
                 ORDER BY organization_id, id
                """,
                (row, ignored) -> new TeamCoordinate(
                        OrganizationId.from(row.getString(1)), TeamId.from(row.getString(2))));
        teams.forEach(this::repair);
    }

    private void repair(TeamCoordinate team) {
        try {
            provisioning.ensureReady(team.organizationId(), team.teamId());
        } catch (RuntimeException unavailable) {
            // A missing or unhealthy model must not make the whole application unavailable.
            LOGGER.warn(
                    "Team Observer remains disabled after startup repair: organizationId={},"
                            + " teamId={}, reason={}",
                    team.organizationId(),
                    team.teamId(),
                    safeReason(unavailable));
        }
    }

    private static String safeReason(RuntimeException failure) {
        return failure instanceof AgentModelPreflightException rejected
                ? rejected.reason().name()
                : failure.getClass().getSimpleName();
    }

    private record TeamCoordinate(OrganizationId organizationId, TeamId teamId) {}
}
