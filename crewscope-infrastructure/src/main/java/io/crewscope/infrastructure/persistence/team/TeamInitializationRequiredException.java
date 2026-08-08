package io.crewscope.infrastructure.persistence.team;

import org.springframework.dao.DataRetrievalFailureException;

import java.util.Objects;
import java.util.UUID;

/** Signals that a migrated Team still needs M1 Owner and default Workspace reconciliation. */
public final class TeamInitializationRequiredException extends DataRetrievalFailureException {

    private final UUID teamId;

    public TeamInitializationRequiredException(UUID teamId) {
        super(
                "Team "
                        + Objects.requireNonNull(teamId, "teamId")
                        + " requires Owner and default Workspace initialization");
        this.teamId = teamId;
    }

    public UUID teamId() {
        return teamId;
    }
}
