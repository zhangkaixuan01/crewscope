package io.crewscope.application.action;

import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionBundleId;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Optional;

/** Persistence port for immutable Bundle graphs with explicit tenant boundaries. */
public interface ActionBundleRepository {

    Optional<ActionBundle> findById(OrganizationId organizationId, ActionBundleId id);

    Optional<ActionBundle> findByReviewDecision(
            OrganizationId organizationId, ReviewDecisionId reviewDecisionId);

    List<ActionBundle> findByTaskExecution(
            OrganizationId organizationId, TaskExecutionId taskExecutionId);

    void insert(ActionBundle bundle);
}
