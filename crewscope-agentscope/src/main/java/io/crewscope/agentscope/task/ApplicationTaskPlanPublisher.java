package io.crewscope.agentscope.task;

import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.task.TaskPlanPublicationCommand;
import io.crewscope.application.task.TaskPlanPublicationService;
import io.crewscope.domain.task.PlanChangeReason;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.task.TaskExecutionPlanningContext;
import java.util.Objects;

/** Builds the complete stale-fact guard and delegates publication to the application transaction. */
public final class ApplicationTaskPlanPublisher implements TaskPlanPublisher {

    private final TaskPlanPublicationService publicationService;
    private final int maxStepRunAttempts;

    public ApplicationTaskPlanPublisher(
            TaskPlanPublicationService publicationService, int maxStepRunAttempts) {
        this.publicationService = Objects.requireNonNull(publicationService, "publicationService");
        if (maxStepRunAttempts < 1 || maxStepRunAttempts > 100) {
            throw new IllegalArgumentException("maxStepRunAttempts must be between 1 and 100");
        }
        this.maxStepRunAttempts = maxStepRunAttempts;
    }

    @Override
    public PlanVersionId publish(
            TaskExecutionRuntimeFacts facts,
            AgentScopeTaskPlanAdapter.Candidate candidate) {
        TaskExecutionRuntimeFacts required = Objects.requireNonNull(facts, "facts");
        AgentScopeTaskPlanAdapter.Candidate proposed = Objects.requireNonNull(candidate, "candidate");
        TaskExecutionPlanningContext planning = required.execution().planningContext().orElseThrow();
        PlanChangeReason reason = planning.currentPlanVersionId().isEmpty()
                ? PlanChangeReason.INITIAL_PLAN
                : PlanChangeReason.RECOVERY_REPLAN;
        return publicationService.publish(new TaskPlanPublicationCommand(
                        required.execution().scope().organizationId(),
                        required.task().id(),
                        required.execution().id(),
                        required.execution().version(),
                        planning.currentPlanVersionId(),
                        required.policySnapshot().id(),
                        required.policySnapshot().snapshotHash(),
                        required.safetyOverlay().reference(),
                        required.policySnapshot().agentProfileId(),
                        required.policySnapshot().agentProfileVersion(),
                        reason,
                        proposed.plan(),
                        proposed.todos(),
                        maxStepRunAttempts))
                .planVersion()
                .id();
    }
}
