package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.Optional;

/** Mutable TaskExecution pointers to immutable policy, safety and plan versions. */
public record TaskExecutionPlanningContext(
        ExecutionPrincipalSnapshot executionPrincipal,
        PolicySnapshotId policySnapshotId,
        TaskFactHash policySnapshotHash,
        SafetyEnforcementOverlayReference safetyOverlay,
        Optional<PlanVersionId> currentPlanVersionId,
        Optional<TaskFactHash> currentPlanVersionHash) {

    public TaskExecutionPlanningContext {
        executionPrincipal = Objects.requireNonNull(executionPrincipal, "executionPrincipal");
        policySnapshotId = Objects.requireNonNull(policySnapshotId, "policySnapshotId");
        policySnapshotHash = Objects.requireNonNull(policySnapshotHash, "policySnapshotHash");
        safetyOverlay = Objects.requireNonNull(safetyOverlay, "safetyOverlay");
        currentPlanVersionId = Objects.requireNonNull(
                currentPlanVersionId, "currentPlanVersionId");
        currentPlanVersionHash = Objects.requireNonNull(
                currentPlanVersionHash, "currentPlanVersionHash");
        if (currentPlanVersionId.isPresent() != currentPlanVersionHash.isPresent()) {
            throw new DomainValidationException(
                    "taskExecution.planningContext.currentPlanVersion",
                    "ID and hash must be present together");
        }
    }

    static TaskExecutionPlanningContext initial(
            PolicySnapshot policy, SafetyEnforcementOverlay overlay) {
        return new TaskExecutionPlanningContext(
                policy.executionPrincipal(),
                policy.id(),
                policy.snapshotHash(),
                overlay.reference(),
                Optional.empty(),
                Optional.empty());
    }

    TaskExecutionPlanningContext withPolicy(PolicySnapshot policy) {
        return new TaskExecutionPlanningContext(
                policy.executionPrincipal(),
                policy.id(),
                policy.snapshotHash(),
                safetyOverlay,
                Optional.empty(),
                Optional.empty());
    }

    TaskExecutionPlanningContext withOverlay(SafetyEnforcementOverlay overlay) {
        return new TaskExecutionPlanningContext(
                executionPrincipal,
                policySnapshotId,
                policySnapshotHash,
                overlay.reference(),
                Optional.empty(),
                Optional.empty());
    }

    TaskExecutionPlanningContext withPlan(PlanVersion plan) {
        return new TaskExecutionPlanningContext(
                executionPrincipal,
                policySnapshotId,
                policySnapshotHash,
                safetyOverlay,
                Optional.of(plan.id()),
                Optional.of(plan.versionHash()));
    }
}
