package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.util.List;
import java.util.Optional;

/** Tenant-scoped query and deterministic rebuild Port for the Review workbench projection. */
public interface ReviewQueryRepository {

    Optional<ReviewRequestProjection> findByRequest(
            OrganizationId organizationId, ReviewRequestId reviewRequestId);

    List<ReviewRequestProjection> findByExecution(
            OrganizationId organizationId, TaskExecutionId taskExecutionId, int attempt);

    List<ReviewRequestProjection> findHistoryByTask(
            OrganizationId organizationId, TaskId taskId, int limit);

    void rebuild(OrganizationId organizationId, ReviewRequestId reviewRequestId);

    int rebuildAll(OrganizationId organizationId);
}
