package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Optional;

/** Tenant-scoped port enforcing one current ReviewRequest per execution attempt. */
public interface ReviewRequestRepository {

    Optional<ReviewRequest> findById(OrganizationId organizationId, ReviewRequestId id);

    Optional<ReviewRequest> findCurrentByExecution(
            OrganizationId organizationId, TaskExecutionId taskExecutionId, int attempt);

    void insert(ReviewRequest request);

    void update(ReviewRequest request, long expectedVersion);
}
