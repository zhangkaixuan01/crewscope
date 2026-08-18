package io.crewscope.application.coding;

import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for RepositoryBinding aggregates with explicit tenant and project scope. */
public interface RepositoryBindingRepository {

    /**
     * Inserts a binding and atomically rejects a duplicate WorkProject and Repository Key pair.
     */
    RepositoryBinding create(RepositoryBinding binding);

    /** Commits a mutation using the aggregate's previous version as the lock predicate. */
    RepositoryBinding update(RepositoryBinding binding);

    Optional<RepositoryBinding> findById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            RepositoryBindingId bindingId);

    Optional<RepositoryBinding> findByKey(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            RepositoryKey repositoryKey);

    List<RepositoryBinding> findByWorkProject(
            OrganizationId organizationId, TeamId teamId, WorkProjectId workProjectId);
}
