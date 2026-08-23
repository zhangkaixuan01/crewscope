package io.crewscope.application.review;

import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Optional;

/** Tenant-scoped persistence port for append-only ContextPackage versions. */
public interface ContextPackageRepository {

    Optional<ContextPackage> findById(OrganizationId organizationId, ContextPackageId id);

    Optional<ContextPackage> findLatestByExecution(
            OrganizationId organizationId, TaskExecutionId taskExecutionId, int attempt);

    void save(ContextPackage contextPackage);
}
