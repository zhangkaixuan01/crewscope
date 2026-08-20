package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.BuildProfileCatalog;
import io.crewscope.application.coding.CodingTargetSnapshotRepository;
import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.WorkspacePolicyOverlayRepository;
import io.crewscope.application.coding.WorkspacePolicyRepository;
import io.crewscope.application.execution.TaskExecutionTerminalStatus;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** M4-A03 proof for recover, activate, suspend and terminal Workspace orchestration. */
class DurableCodingWorkspaceExecutionLifecycleM4A03Test {

    private final WorkItemScope scope = new WorkItemScope(
            OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(), WorkProjectId.generate());
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private final CodingTargetSnapshotRepository targets = mock(CodingTargetSnapshotRepository.class);
    private final ExecutionWorkspaceRepository workspaces = mock(ExecutionWorkspaceRepository.class);
    private final WorkspacePolicyRepository policies = mock(WorkspacePolicyRepository.class);
    private final WorkspacePolicyOverlayRepository overlays = mock(WorkspacePolicyOverlayRepository.class);
    private final BuildProfileCatalog profiles = mock(BuildProfileCatalog.class);
    private final ManagedRepositoryResolver repositories = mock(ManagedRepositoryResolver.class);
    private final WorktreeProvisioner worktrees = mock(WorktreeProvisioner.class);
    private final TaskExecutionSandboxFactory sandboxes = mock(TaskExecutionSandboxFactory.class);
    private final WorkspaceDiffMonitorFactory monitors = mock(WorkspaceDiffMonitorFactory.class);
    private final WorkspaceDiffFinalizer finalizer = mock(WorkspaceDiffFinalizer.class);
    private final CodingWorkspaceRuntimeRegistry registry = new CodingWorkspaceRuntimeRegistry();
    private final CodingFilesystemUsageRegistry filesystemUsages = mock(CodingFilesystemUsageRegistry.class);
    private final SandboxCommandUsageRegistry commandUsages = mock(SandboxCommandUsageRegistry.class);
    private final Principal actor = mock(Principal.class);
    private final ExecutionLease lease = mock(ExecutionLease.class);
    private final WorkspacePolicy policy = mock(WorkspacePolicy.class);
    private final CodingTargetSnapshot target = mock(CodingTargetSnapshot.class);
    private final BuildProfile profile = mock(BuildProfile.class);
    private final ManagedRepository repository = mock(ManagedRepository.class);
    private final ManagedWorktree worktree = mock(ManagedWorktree.class);
    private final ManagedTaskExecutionSandbox sandbox = mock(ManagedTaskExecutionSandbox.class);
    private DurableCodingWorkspaceExecutionLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        TransactionExecutor transactions = mock(TransactionExecutor.class);
        when(transactions.required(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        AuthoritativeTimeProvider time = mock(AuthoritativeTimeProvider.class);
        when(time.now()).thenReturn(UtcTimestamp.parse("2026-08-20T00:00:00Z"));
        RuntimeWorkerRegistrationSpec registration = mock(RuntimeWorkerRegistrationSpec.class);
        when(registration.actor()).thenReturn(actor);
        lifecycle = new DurableCodingWorkspaceExecutionLifecycle(
                targets,
                workspaces,
                policies,
                overlays,
                profiles,
                repositories,
                worktrees,
                sandboxes,
                monitors,
                finalizer,
                registry,
                filesystemUsages,
                commandUsages,
                transactions,
                time,
                registration,
                new CodingWorkspaceExecutionProperties(),
                io.crewscope.application.coding.CodingTaskTimelinePublisher.NO_OP);
    }

    @Test
    void resumesReadyWorkspaceThenActivatesBeforeItBecomesToolVisible() {
        TaskExecution preparing = execution(TaskExecutionStatus.PREPARING);
        TaskExecution running = execution(TaskExecutionStatus.RUNNING);
        PolicySnapshot taskPolicy = mock(PolicySnapshot.class);
        ExecutionWorkspace retained = workspace(ExecutionWorkspaceStatus.READY);
        ExecutionWorkspace rebound = workspace(ExecutionWorkspaceStatus.READY);
        ExecutionWorkspace active = workspace(ExecutionWorkspaceStatus.ACTIVE);
        WorkspaceDiffMonitor monitor = mock(WorkspaceDiffMonitor.class);
        var profileReference = new io.crewscope.domain.coding.BuildProfileReference(
                "maven-java-17", 1, io.crewscope.domain.task.TaskFactHash.sha256("profile"));

        when(targets.findLatestByTask(scope.organizationId(), scope.teamId(), scope.projectId(),
                        preparing.taskId()))
                .thenReturn(Optional.of(target));
        when(target.buildProfile()).thenReturn(profileReference);
        when(profiles.findExact(profileReference)).thenReturn(Optional.of(profile));
        when(policies.findByTaskExecution(
                        scope.organizationId(), scope.teamId(), scope.projectId(), executionId))
                .thenReturn(Optional.of(policy));
        when(workspaces.findByTaskExecutionForUpdate(
                        scope.organizationId(), scope.teamId(), scope.projectId(), executionId))
                .thenReturn(Optional.of(retained));
        when(retained.rebindForResume(eq(preparing), eq(lease), any(Long.class), eq(actor), any()))
                .thenReturn(rebound);
        when(workspaces.update(rebound)).thenReturn(rebound);
        when(worktrees.verify(rebound, policy)).thenReturn(worktree);
        when(sandboxes.recover(
                        eq(rebound), eq(worktree), eq(policy), eq(profile), eq(lease), any()))
                .thenReturn(sandbox);
        when(repositories.resolve(rebound.repositoryKey())).thenReturn(repository);
        when(rebound.activate(eq(running), eq(lease), any(Long.class), eq(actor), any()))
                .thenReturn(active);
        when(workspaces.update(active)).thenReturn(active);
        when(monitors.open(active, worktree, policy)).thenReturn(monitor);

        CodingWorkspaceExecution prepared = lifecycle.prepare(preparing, lease, taskPolicy)
                .orElseThrow();
        assertTrue(registry.find(executionId).isEmpty());

        lifecycle.activate(prepared, running, lease);

        assertEquals(active, prepared.workspace());
        assertEquals(prepared, registry.find(executionId).orElseThrow());
        InOrder order = inOrder(workspaces, worktrees, sandboxes, monitors);
        order.verify(workspaces).update(rebound);
        order.verify(worktrees).verify(rebound, policy);
        order.verify(sandboxes).recover(
                eq(rebound), eq(worktree), eq(policy), eq(profile), eq(lease), any());
        order.verify(workspaces).update(active);
        order.verify(monitors).open(active, worktree, policy);
    }

    @Test
    void pauseStopsEffectsThenReturnsWorkspaceToReadyAfterDurableRelease() {
        ExecutionWorkspace active = workspace(ExecutionWorkspaceStatus.ACTIVE);
        ExecutionWorkspace ready = workspace(ExecutionWorkspaceStatus.READY);
        TaskExecution running = execution(TaskExecutionStatus.RUNNING);
        TaskExecution paused = execution(TaskExecutionStatus.PAUSED);
        CodingWorkspaceExecution execution = codingExecution(active);
        WorkspaceDiffMonitor monitor = mock(WorkspaceDiffMonitor.class);
        execution.diffMonitor(monitor);
        registry.register(execution);
        when(active.preserveForPause(eq(paused), any(Long.class), eq(actor), any()))
                .thenReturn(ready);
        when(workspaces.update(ready)).thenReturn(ready);

        lifecycle.beforeRelease(execution, running, lease, TaskExecutionTerminalStatus.PAUSED);
        lifecycle.afterRelease(execution, paused, TaskExecutionTerminalStatus.PAUSED);

        verify(monitor).close();
        verify(sandboxes).pause(sandbox, active, lease, UtcTimestamp.parse("2026-08-20T00:00:00Z"));
        assertEquals(ready, execution.workspace());
        assertTrue(registry.find(executionId).isEmpty());
    }

    @Test
    void completionFreezesDiffBeforeLeaseReleaseAndCommitsWorkspaceTerminalAfterward() {
        ExecutionWorkspace active = workspace(ExecutionWorkspaceStatus.ACTIVE);
        ExecutionWorkspace finalizing = workspace(ExecutionWorkspaceStatus.FINALIZING);
        ExecutionWorkspace completed = workspace(ExecutionWorkspaceStatus.COMPLETED);
        TaskExecution running = execution(TaskExecutionStatus.RUNNING);
        TaskExecution terminal = execution(TaskExecutionStatus.COMPLETED);
        CodingWorkspaceExecution execution = codingExecution(active);
        WorkspaceDiffMonitor monitor = mock(WorkspaceDiffMonitor.class);
        DiffManifest live = DiffManifest.initial(java.util.List.of());
        when(monitor.latest()).thenReturn(Optional.of(live));
        execution.diffMonitor(monitor);
        WorktreeArchiveResult archive = mock(WorktreeArchiveResult.class);
        DiffArtifact diff = mock(DiffArtifact.class);
        when(active.beginFinalizing(any(), eq(running), any(Long.class), eq(actor), any()))
                .thenReturn(finalizing);
        when(workspaces.update(finalizing)).thenReturn(finalizing);
        when(worktrees.archive(finalizing, policy)).thenReturn(archive);
        when(finalizer.finalizeDiff(finalizing, target, policy, archive, actor, Optional.of(live)))
                .thenReturn(diff);
        when(finalizing.completeFinalizing(eq(terminal), any(Long.class), eq(actor), any()))
                .thenReturn(completed);
        when(workspaces.update(completed)).thenReturn(completed);

        lifecycle.beforeRelease(execution, running, lease, TaskExecutionTerminalStatus.COMPLETED);
        assertEquals(diff, execution.finalDiff().orElseThrow());
        lifecycle.afterRelease(execution, terminal, TaskExecutionTerminalStatus.COMPLETED);

        InOrder order = inOrder(monitor, workspaces, sandboxes, worktrees, finalizer);
        order.verify(monitor).close();
        order.verify(workspaces).update(finalizing);
        order.verify(sandboxes).destroy(sandbox, finalizing);
        order.verify(worktrees).archive(finalizing, policy);
        order.verify(finalizer).finalizeDiff(
                finalizing, target, policy, archive, actor, Optional.of(live));
        order.verify(workspaces).update(completed);
        assertEquals(completed, execution.workspace());
    }

    @Test
    void workerReleaseDoesNotRepeatAnAlreadySealedFinalization() {
        ExecutionWorkspace finalizing = workspace(ExecutionWorkspaceStatus.FINALIZING);
        TaskExecution running = execution(TaskExecutionStatus.RUNNING);
        CodingWorkspaceExecution execution = codingExecution(finalizing);
        DiffArtifact diff = mock(DiffArtifact.class);
        execution.finalDiff(diff);

        lifecycle.beforeRelease(execution, running, lease, TaskExecutionTerminalStatus.COMPLETED);

        assertEquals(diff, execution.finalDiff().orElseThrow());
        verifyNoInteractions(sandboxes, worktrees, finalizer);
    }

    private TaskExecution execution(TaskExecutionStatus status) {
        TaskExecution execution = mock(TaskExecution.class);
        when(execution.scope()).thenReturn(scope);
        when(execution.id()).thenReturn(executionId);
        when(execution.taskId()).thenReturn(io.crewscope.domain.task.TaskId.generate());
        when(execution.status()).thenReturn(status);
        return execution;
    }

    private ExecutionWorkspace workspace(ExecutionWorkspaceStatus status) {
        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        when(workspace.scope()).thenReturn(scope);
        when(workspace.taskExecutionId()).thenReturn(executionId);
        when(workspace.status()).thenReturn(status);
        when(workspace.version()).thenReturn(1L);
        when(workspace.repositoryKey()).thenReturn(new io.crewscope.domain.coding.RepositoryKey(
                "m4-a03-lifecycle"));
        return workspace;
    }

    private CodingWorkspaceExecution codingExecution(ExecutionWorkspace workspace) {
        return new CodingWorkspaceExecution(
                workspace, target, policy, profile, repository, worktree, sandbox);
    }
}
