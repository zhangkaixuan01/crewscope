package io.crewscope.application.execution;

import io.crewscope.application.task.TaskTokenExecutionContext;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunSegmentStatus;
import io.crewscope.domain.task.AgentRunStatus;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionPlanningContext;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskTokenGrantScope;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-resolved immutable facts for one Task AgentRun Segment.
 *
 * <p>The runtime consumes this closed snapshot and never reconstructs tenant, ownership, Lease,
 * policy, Agent or authorization coordinates from a request body.
 */
public record TaskExecutionRuntimeFacts(
        Task task,
        TaskExecution execution,
        Optional<StepExecution> stepExecution,
        ExecutionLease lease,
        TaskAgentRuntimeSession runtimeSession,
        AgentRun agentRun,
        PolicySnapshot policySnapshot,
        SafetyEnforcementOverlay safetyOverlay,
        Optional<PlanVersion> planVersion,
        TaskTokenExecutionContext authorization) {

    private static final EnumSet<TaskExecutionStatus> EXECUTABLE_STATUSES = EnumSet.of(
            TaskExecutionStatus.CLAIMED,
            TaskExecutionStatus.PREPARING,
            TaskExecutionStatus.RUNNING,
            TaskExecutionStatus.PAUSE_REQUESTED,
            TaskExecutionStatus.CANCEL_REQUESTED);

    public TaskExecutionRuntimeFacts {
        task = Objects.requireNonNull(task, "task");
        execution = Objects.requireNonNull(execution, "execution");
        stepExecution = Objects.requireNonNull(stepExecution, "stepExecution");
        lease = Objects.requireNonNull(lease, "lease");
        runtimeSession = Objects.requireNonNull(runtimeSession, "runtimeSession");
        agentRun = Objects.requireNonNull(agentRun, "agentRun");
        policySnapshot = Objects.requireNonNull(policySnapshot, "policySnapshot");
        safetyOverlay = Objects.requireNonNull(safetyOverlay, "safetyOverlay");
        planVersion = Objects.requireNonNull(planVersion, "planVersion");
        authorization = Objects.requireNonNull(authorization, "authorization");
        requireTaskExecution(task, execution);
        TaskExecutionPlanningContext planning = requirePlanning(
                execution, policySnapshot, safetyOverlay, planVersion);
        requireLease(execution, lease);
        requireAuthorization(execution, lease, planning, authorization);
        requireSessionAndRun(
                task, execution, stepExecution, runtimeSession, agentRun, policySnapshot);
        requireStepAndPlan(
                execution, stepExecution, policySnapshot, safetyOverlay, planVersion);
    }

    private static void requireTaskExecution(Task task, TaskExecution execution) {
        boolean current = !task.isClosed()
                && task.scope().equals(execution.scope())
                && task.id().equals(execution.taskId())
                && task.currentExecutionId().filter(execution.id()::equals).isPresent()
                && EXECUTABLE_STATUSES.contains(execution.status());
        if (!current) {
            throw invalid("must reference the open Task's current executable attempt");
        }
    }

    private static TaskExecutionPlanningContext requirePlanning(
            TaskExecution execution,
            PolicySnapshot policy,
            SafetyEnforcementOverlay overlay,
            Optional<PlanVersion> plan) {
        TaskExecutionPlanningContext planning = execution.planningContext()
                .orElseThrow(() -> invalid("requires the current PlanningContext"));
        boolean current = execution.scope().equals(policy.scope())
                && execution.scope().equals(overlay.scope())
                && execution.taskId().equals(policy.taskId())
                && execution.taskId().equals(overlay.taskId())
                && execution.id().equals(policy.executionId())
                && execution.id().equals(overlay.executionId())
                && planning.executionPrincipal().equals(policy.executionPrincipal())
                && planning.policySnapshotId().equals(policy.id())
                && planning.policySnapshotHash().equals(policy.snapshotHash())
                && planning.safetyOverlay().equals(overlay.reference())
                && planning.currentPlanVersionId().equals(plan.map(PlanVersion::id))
                && planning.currentPlanVersionHash().equals(plan.map(PlanVersion::versionHash));
        if (!current) {
            throw invalid("must use the TaskExecution current Policy, Safety and Plan facts");
        }
        return planning;
    }

    private static void requireLease(TaskExecution execution, ExecutionLease lease) {
        boolean current = execution.scope().organizationId().equals(lease.organizationId())
                && execution.id().equals(lease.taskExecutionId())
                && execution.attempt() == lease.attempt()
                && execution.lastFencingToken().filter(lease.fencingToken()::equals).isPresent()
                && lease.release().isEmpty();
        if (!current) {
            throw invalid("must use the current unreleased ExecutionLease and Fencing Token");
        }
    }

    private static void requireAuthorization(
            TaskExecution execution,
            ExecutionLease lease,
            TaskExecutionPlanningContext planning,
            TaskTokenExecutionContext authorization) {
        TaskTokenGrantScope scope = authorization.scope();
        boolean current = scope.workItemScope().equals(execution.scope())
                && scope.taskId().equals(execution.taskId())
                && scope.taskExecutionId().equals(execution.id())
                && scope.attempt() == execution.attempt()
                && scope.executionLeaseId().equals(lease.id())
                && scope.environment().equals(lease.environment())
                && scope.runtimeId().equals(lease.runtimeId())
                && scope.workerId().equals(lease.workerId())
                && scope.claimTokenHash().equals(lease.claimTokenHash())
                && scope.fencingToken().equals(lease.fencingToken())
                && scope.executionPrincipal().equals(planning.executionPrincipal())
                && scope.policySnapshotId().equals(planning.policySnapshotId())
                && scope.policySnapshotHash().equals(planning.policySnapshotHash())
                && scope.safetyOverlay().equals(planning.safetyOverlay())
                && authorization.expiresAt().compareTo(lease.expiresAt()) <= 0;
        if (!current) {
            throw invalid("must use the verified Task Token for the current execution owner");
        }
    }

    private static void requireSessionAndRun(
            Task task,
            TaskExecution execution,
            Optional<StepExecution> step,
            TaskAgentRuntimeSession session,
            AgentRun run,
            PolicySnapshot policy) {
        Optional<io.crewscope.domain.task.StepExecutionId> stepId = step.map(StepExecution::id);
        boolean current = session.scope().equals(execution.scope())
                && session.taskId().equals(task.id())
                && session.executionId().equals(execution.id())
                && session.stepExecutionId().equals(stepId)
                && session.agentPrincipalId().equals(policy.executionPrincipal().principalId())
                && session.agentProfileId().equals(policy.agentProfileId())
                && session.agentProfileVersion() == policy.agentProfileVersion()
                && run.status() == AgentRunStatus.RUNNING
                && run.currentSegment().status() == AgentRunSegmentStatus.ACTIVE
                && run.scope().equals(execution.scope())
                && run.taskId().equals(task.id())
                && run.executionId().equals(execution.id())
                && run.stepExecutionId().equals(stepId)
                && run.runtimeSessionId().equals(session.id())
                && run.agentPrincipalId().equals(session.agentPrincipalId())
                && run.agentProfileId().equals(session.agentProfileId())
                && run.agentProfileVersion() == session.agentProfileVersion();
        if (!current) {
            throw invalid("must use the active Task Agent Session and AgentRun Segment");
        }
    }

    private static void requireStepAndPlan(
            TaskExecution execution,
            Optional<StepExecution> step,
            PolicySnapshot policy,
            SafetyEnforcementOverlay overlay,
            Optional<PlanVersion> plan) {
        plan.ifPresent(value -> {
            boolean currentPlan = value.scope().equals(execution.scope())
                    && value.taskId().equals(execution.taskId())
                    && value.executionId().equals(execution.id())
                    && value.policySnapshotId().equals(policy.id())
                    && value.policySnapshotHash().equals(policy.snapshotHash())
                    && value.safetyOverlay().equals(overlay.reference())
                    && value.executionPrincipal().equals(policy.executionPrincipal());
            if (!currentPlan) {
                throw invalid("must use the current PlanVersion");
            }
        });
        step.ifPresent(value -> {
            PlanVersion currentPlan = plan.orElseThrow(() ->
                    invalid("requires a PlanVersion for a Step Agent Session"));
            boolean currentStep = value.scope().equals(execution.scope())
                    && value.taskId().equals(execution.taskId())
                    && value.executionId().equals(execution.id())
                    && value.planVersionId().equals(currentPlan.id())
                    && value.planVersionHash().equals(currentPlan.versionHash())
                    && value.executionPrincipal().equals(policy.executionPrincipal())
                    && value.policySnapshotId().equals(policy.id())
                    && value.policySnapshotHash().equals(policy.snapshotHash())
                    && value.safetyOverlay().equals(overlay.reference());
            if (!currentStep) {
                throw invalid("must use a Step from the current Plan and authorization facts");
            }
        });
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("taskExecutionRuntimeFacts " + message);
    }

    @Override
    public String toString() {
        return "TaskExecutionRuntimeFacts[taskExecutionId=" + execution.id()
                + ", attempt=" + execution.attempt()
                + ", agentRunId=" + agentRun.id()
                + ", segment=" + agentRun.currentSegment().sequence()
                + ", authorization=[REDACTED]]";
    }
}
