package io.crewscope.application.team;

import io.crewscope.domain.workspace.Workspace;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.util.Optional;

/** Persistence Port for product Workspace aggregates. */
public interface WorkspaceRepository {

    Workspace create(Workspace workspace);

    /** Commits one Workspace lifecycle change with an optimistic version predicate. */
    default Workspace update(Workspace workspace) {
        throw new UnsupportedOperationException("Workspace update is not implemented");
    }

    /** Finds one Workspace only inside the explicit Organization boundary. */
    default Optional<Workspace> findById(OrganizationId organizationId, WorkspaceId id) {
        throw new UnsupportedOperationException("Workspace lookup is not implemented");
    }
}
