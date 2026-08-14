package io.crewscope.application.task;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for immutable published PlanVersion facts. */
public interface PlanVersionRepository {
    PlanVersion create(PlanVersion planVersion);

    Optional<PlanVersion> findById(OrganizationId organizationId, PlanVersionId planVersionId);

    /** Returns all versions for one execution ordered by ascending revision. */
    List<PlanVersion> findByExecution(
            OrganizationId organizationId, TaskExecutionId executionId);
}
