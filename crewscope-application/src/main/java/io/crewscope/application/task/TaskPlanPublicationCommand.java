package io.crewscope.application.task;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.PlanChangeReason;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.ProposedPlan;
import io.crewscope.domain.task.SafetyEnforcementOverlayReference;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TodoSummaryItem;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Runtime plan candidate together with every immutable fact it was generated against. */
public record TaskPlanPublicationCommand(
        OrganizationId organizationId,
        TaskId taskId,
        TaskExecutionId executionId,
        long expectedExecutionVersion,
        Optional<PlanVersionId> expectedCurrentPlanVersionId,
        PolicySnapshotId policySnapshotId,
        TaskFactHash policySnapshotHash,
        SafetyEnforcementOverlayReference safetyOverlay,
        AgentProfileId agentProfileId,
        long agentProfileVersion,
        PlanChangeReason changeReason,
        ProposedPlan candidate,
        List<TodoSummaryItem> todoSummary,
        int maxStepRunAttempts) {

    public TaskPlanPublicationCommand {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        taskId = Objects.requireNonNull(taskId, "taskId");
        executionId = Objects.requireNonNull(executionId, "executionId");
        if (expectedExecutionVersion < 0) {
            throw new IllegalArgumentException("expectedExecutionVersion must not be negative");
        }
        expectedCurrentPlanVersionId = Objects.requireNonNull(
                expectedCurrentPlanVersionId, "expectedCurrentPlanVersionId");
        policySnapshotId = Objects.requireNonNull(policySnapshotId, "policySnapshotId");
        policySnapshotHash = Objects.requireNonNull(policySnapshotHash, "policySnapshotHash");
        safetyOverlay = Objects.requireNonNull(safetyOverlay, "safetyOverlay");
        agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        if (agentProfileVersion < 0) {
            throw new IllegalArgumentException("agentProfileVersion must not be negative");
        }
        changeReason = Objects.requireNonNull(changeReason, "changeReason");
        candidate = Objects.requireNonNull(candidate, "candidate");
        todoSummary = List.copyOf(Objects.requireNonNull(todoSummary, "todoSummary"));
        if (maxStepRunAttempts < 1 || maxStepRunAttempts > 100) {
            throw new IllegalArgumentException("maxStepRunAttempts must be between 1 and 100");
        }
        if ((expectedCurrentPlanVersionId.isEmpty())
                != (changeReason == PlanChangeReason.INITIAL_PLAN)) {
            throw new IllegalArgumentException(
                    "changeReason must match initial or replacement plan publication");
        }
    }
}
