package io.crewscope.application.action;

import io.crewscope.domain.action.ActionClaimMode;
import io.crewscope.domain.action.ActionKind;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;

/** Safe correlation spine linking model execution, Review and external Action diagnostics. */
public record ActionReconciliationTrace(
        OrganizationId organizationId,
        TeamId teamId,
        TaskExecutionId taskExecutionId,
        ReviewDecisionId reviewDecisionId,
        PlannedActionId actionId,
        ActionKind actionKind,
        ActionClaimMode claimMode) {

    public ActionReconciliationTrace {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        reviewDecisionId = Objects.requireNonNull(reviewDecisionId, "reviewDecisionId");
        actionId = Objects.requireNonNull(actionId, "actionId");
        actionKind = Objects.requireNonNull(actionKind, "actionKind");
        claimMode = Objects.requireNonNull(claimMode, "claimMode");
    }
}
