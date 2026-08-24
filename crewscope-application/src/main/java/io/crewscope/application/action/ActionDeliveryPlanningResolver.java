package io.crewscope.application.action;

import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;

/** Resolves current Review, Provider, policy, target and managed Workspace facts for planning. */
public interface ActionDeliveryPlanningResolver {

    ActionDeliveryPlanningFacts resolve(
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            ReviewDecisionId reviewDecisionId,
            ProviderBindingId providerBindingId,
            ExternalRepositoryId externalRepositoryId);
}
