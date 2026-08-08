package io.crewscope.application.workitem;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.List;
import java.util.Optional;

/** Persistence Port for tenant-scoped WorkProject aggregates. */
public interface WorkProjectRepository {

    WorkProject create(WorkProject project);

    WorkProject update(WorkProject project);

    Optional<WorkProject> findById(OrganizationId organizationId, WorkProjectId id);

    /** Locks one project as the serialization point for native WorkItem key allocation. */
    default Optional<WorkProject> lockById(
            OrganizationId organizationId, WorkProjectId id) {
        throw new UnsupportedOperationException("WorkProject lock is not implemented");
    }

    Optional<WorkProject> findByKey(
            OrganizationId organizationId, TeamId teamId, WorkProjectKey key);

    List<WorkProject> findByTeam(OrganizationId organizationId, TeamId teamId);

    /** Returns one deterministic keyset-paginated slice. */
    WorkProjectPage findPage(WorkProjectQuery query);
}
