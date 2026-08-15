package io.crewscope.infrastructure.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ClaimTokenHash;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionPrincipalSnapshot;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.SafetyEnforcementOverlayReference;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPlanningContext;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import java.util.Set;

/** Minimal current Task/Lease/Policy facts used by M3-I04 authority tests. */
final class TaskTokenRuntimeFixture {

    final UtcTimestamp now = UtcTimestamp.parse("2026-08-15T03:00:00Z");
    final OrganizationId organizationId = OrganizationId.generate();
    final RuntimeEnvironment environment = new RuntimeEnvironment("test");
    final WorkItemScope workScope = new WorkItemScope(
            organizationId, TeamId.generate(), WorkspaceId.generate(), WorkProjectId.generate());
    final Principal actor = Principal.create(
            PrincipalId.generate(), PrincipalScope.organization(organizationId),
            PrincipalType.SERVICE, Optional.empty(), "Task Runtime", Optional.empty(),
            PrincipalVisibility.ORGANIZATION, now);
    final TaskId taskId = TaskId.generate();
    final TaskExecutionId executionId = TaskExecutionId.generate();
    final ExecutionLeaseId leaseId = ExecutionLeaseId.generate();
    final ExecutionRuntimeId runtimeId = ExecutionRuntimeId.generate();
    final RuntimeWorkerId workerId = RuntimeWorkerId.generate();
    final FencingToken fencingToken = FencingToken.initial();
    final ClaimTokenHash claimTokenHash = new ClaimTokenHash("b".repeat(64));
    final ExecutionPrincipalSnapshot executionPrincipal = new ExecutionPrincipalSnapshot(
            actor.id(), ResponsibilityAssignmentId.generate(), 1,
            TaskFactHash.sha256("responsibility"));
    final PolicySnapshotId policyId = PolicySnapshotId.generate();
    final TaskFactHash policyHash = TaskFactHash.sha256("policy");
    final SafetyEnforcementOverlayReference overlayReference =
            new SafetyEnforcementOverlayReference(
                    SafetyEnforcementOverlayId.generate(), 1, TaskFactHash.sha256("overlay"));
    final TaskExecutionPlanningContext planningContext = new TaskExecutionPlanningContext(
            executionPrincipal, policyId, policyHash, overlayReference,
            Optional.empty(), Optional.empty());
    final TaskExecution execution = execution();
    final ExecutionLease lease = lease();
    final PolicySnapshot policy = policy();
    final SafetyEnforcementOverlay overlay = overlay();

    private TaskExecution execution() {
        TaskExecution value = mock(TaskExecution.class);
        when(value.id()).thenReturn(executionId);
        when(value.taskId()).thenReturn(taskId);
        when(value.scope()).thenReturn(workScope);
        when(value.attempt()).thenReturn(1);
        when(value.status()).thenReturn(TaskExecutionStatus.CLAIMED);
        when(value.lastFencingToken()).thenReturn(Optional.of(fencingToken));
        when(value.planningContext()).thenReturn(Optional.of(planningContext));
        return value;
    }

    private ExecutionLease lease() {
        ExecutionLease value = mock(ExecutionLease.class);
        when(value.id()).thenReturn(leaseId);
        when(value.organizationId()).thenReturn(organizationId);
        when(value.environment()).thenReturn(environment);
        when(value.taskExecutionId()).thenReturn(executionId);
        when(value.attempt()).thenReturn(1);
        when(value.runtimeId()).thenReturn(runtimeId);
        when(value.workerId()).thenReturn(workerId);
        when(value.claimTokenHash()).thenReturn(claimTokenHash);
        when(value.fencingToken()).thenReturn(fencingToken);
        when(value.expiresAt()).thenReturn(UtcTimestamp.parse("2026-08-15T03:02:00Z"));
        when(value.isActiveAt(any())).thenReturn(true);
        return value;
    }

    private PolicySnapshot policy() {
        PolicySnapshot value = mock(PolicySnapshot.class);
        when(value.id()).thenReturn(policyId);
        when(value.scope()).thenReturn(workScope);
        when(value.taskId()).thenReturn(taskId);
        when(value.executionId()).thenReturn(executionId);
        when(value.executionPrincipal()).thenReturn(executionPrincipal);
        when(value.snapshotHash()).thenReturn(policyHash);
        when(value.allowedTools()).thenReturn(Set.of("repository.read", "validation.run"));
        when(value.providerBindingIds()).thenReturn(Set.of());
        return value;
    }

    private SafetyEnforcementOverlay overlay() {
        SafetyEnforcementOverlay value = mock(SafetyEnforcementOverlay.class);
        when(value.scope()).thenReturn(workScope);
        when(value.taskId()).thenReturn(taskId);
        when(value.executionId()).thenReturn(executionId);
        when(value.reference()).thenReturn(overlayReference);
        when(value.permits(any(), any(), any())).thenReturn(true);
        return value;
    }
}
