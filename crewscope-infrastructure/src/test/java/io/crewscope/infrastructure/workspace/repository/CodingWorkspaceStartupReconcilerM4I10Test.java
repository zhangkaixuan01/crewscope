package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.application.artifact.ArtifactPurgeRequest;
import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.WorkspacePolicyRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import io.crewscope.infrastructure.runtime.TaskWorkerStartupReconciler;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Fault, idempotency, orphan and bounded-capacity proof for M4-I10 startup reconciliation. */
class CodingWorkspaceStartupReconcilerM4I10Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-19T04:00:00Z");
    private static final OrganizationId ORGANIZATION = OrganizationId.generate();
    private static final RuntimeEnvironment ENVIRONMENT = new RuntimeEnvironment("test");

    private final TaskWorkerStartupReconciler tasks = mock(TaskWorkerStartupReconciler.class);
    private final ExecutionWorkspaceRepository workspaces = mock(ExecutionWorkspaceRepository.class);
    private final WorkspacePolicyRepository policies = mock(WorkspacePolicyRepository.class);
    private final WorktreeProvisioner worktrees = mock(WorktreeProvisioner.class);
    private final WorkspaceDiffMonitorFactory diffs = mock(WorkspaceDiffMonitorFactory.class);
    private final DockerSandboxControl docker = mock(DockerSandboxControl.class);
    private final CodingArtifactLifecycle artifacts = mock(CodingArtifactLifecycle.class);
    private final TransactionExecutor transactions = mock(TransactionExecutor.class);
    private final AuthoritativeTimeProvider time = () -> NOW;
    private final RuntimeWorkerRegistrationSpec registration = mock(RuntimeWorkerRegistrationSpec.class);
    private final Principal actor = mock(Principal.class);

    @BeforeEach
    void setUp() {
        when(registration.organizationId()).thenReturn(ORGANIZATION);
        when(registration.environment()).thenReturn(ENVIRONMENT);
        when(registration.actor()).thenReturn(actor);
        when(transactions.required(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(workspaces.findRecoveringForUpdate(any(), any(), any(Integer.class)))
                .thenReturn(List.of());
        when(workspaces.findRetentionDueForUpdate(any(), any(), any(), any(Integer.class)))
                .thenReturn(List.of());
        when(docker.listManaged(ORGANIZATION.toString(), ENVIRONMENT.value()))
                .thenReturn(List.of());
        when(artifacts.purge(any(ArtifactPurgeRequest.class))).thenReturn(List.of());
    }

    @Test
    void closesInterruptedCommandContainerRebuildsDiffAndRemainsIdempotent() {
        ExecutionWorkspace workspace = recovering(ExecutionWorkspaceStatus.ACTIVE);
        WorkspacePolicy policy = policy(workspace);
        ManagedWorktree worktree = mock(ManagedWorktree.class);
        when(workspaces.findRecoveringForUpdate(ORGANIZATION, ENVIRONMENT, 100))
                .thenReturn(List.of(workspace));
        when(docker.inspect(any())).thenReturn(Optional.empty());
        when(worktrees.verify(workspace, policy)).thenReturn(worktree);
        when(tasks.reconcile()).thenReturn(1);

        CodingWorkspaceStartupReconciler reconciler = reconciler(new CodingWorkspaceStartupProperties());

        assertEquals(1, reconciler.reconcile());
        assertEquals(1, reconciler.reconcile());
        assertTrue(reconciler.health().completed());
        assertEquals(1, reconciler.health().recoveredWorkspaces());
        verify(diffs, org.mockito.Mockito.times(2)).reconcileOnce(workspace, worktree, policy);
        verify(worktrees, never()).archive(any(), any());
    }

    @Test
    void failsClosedWhenRetainedWorktreeCannotBeVerified() {
        ExecutionWorkspace workspace = recovering(ExecutionWorkspaceStatus.FINALIZING);
        ExecutionWorkspace failed = mock(ExecutionWorkspace.class);
        WorkspacePolicy policy = policy(workspace);
        when(workspaces.findRecoveringForUpdate(ORGANIZATION, ENVIRONMENT, 100))
                .thenReturn(List.of(workspace));
        when(docker.inspect(any())).thenReturn(Optional.empty());
        when(worktrees.verify(workspace, policy))
                .thenThrow(new WorktreeOperationException(
                        WorktreeOperationError.CORRUPT_HEAD,
                        "sensitive host detail must not escape"));
        when(workspace.fail(any(), any(Long.class), any(), any())).thenReturn(failed);

        CodingWorkspaceStartupReconciler reconciler = reconciler(new CodingWorkspaceStartupProperties());
        reconciler.reconcile();

        verify(workspaces).update(failed);
        assertEquals(1, reconciler.health().failedWorkspaces());
        assertFalse(reconciler.health().lastFailureType().isPresent());
        assertFalse(reconciler.health().toString().contains("sensitive host detail"));
    }

    @Test
    void rollsBackProvisioningArchivesRetentionAndRemovesUnknownManagedSandbox() throws Exception {
        ExecutionWorkspace provisioning = recovering(ExecutionWorkspaceStatus.PROVISIONING);
        WorkspacePolicy provisioningPolicy = policy(provisioning);
        ExecutionWorkspace terminal = terminal();
        WorkspacePolicy terminalPolicy = policy(terminal);
        ExecutionWorkspace archived = mock(ExecutionWorkspace.class);
        when(workspaces.findRecoveringForUpdate(ORGANIZATION, ENVIRONMENT, 100))
                .thenReturn(List.of(provisioning));
        when(workspaces.findRetentionDueForUpdate(ORGANIZATION, ENVIRONMENT, NOW, 100))
                .thenReturn(List.of(terminal));
        when(docker.inspect(any())).thenReturn(Optional.empty());
        when(terminal.archive(NOW, 8L, actor)).thenReturn(archived);

        ExecutionWorkspaceKey orphanKey = new ExecutionWorkspaceKey(
                "ws-00000000-0000-0000-0000-000000000001-a1");
        DockerContainerSnapshot orphan = new DockerContainerSnapshot(new ObjectMapper().readTree("""
                {
                  "Id":"container",
                  "Name":"/agentscope-sandbox-crewscope-00000000000000000000000000000001",
                  "Config":{"Labels":{
                    "io.crewscope.sandbox.managed":"true",
                    "io.crewscope.sandbox.organization-id":"%s",
                    "io.crewscope.sandbox.environment":"%s",
                    "io.crewscope.sandbox.workspace-key":"%s"
                  }}
                }
                """.formatted(ORGANIZATION, ENVIRONMENT.value(), orphanKey.value())));
        when(docker.listManaged(ORGANIZATION.toString(), ENVIRONMENT.value()))
                .thenReturn(List.of(orphan));
        when(workspaces.findByWorkspaceKey(ORGANIZATION, ENVIRONMENT, orphanKey))
                .thenReturn(Optional.empty());

        CodingWorkspaceStartupReconciler reconciler = reconciler(new CodingWorkspaceStartupProperties());
        reconciler.reconcile();

        verify(worktrees).rollbackProvisionOrphan(provisioning, provisioningPolicy);
        verify(worktrees).archive(terminal, terminalPolicy);
        verify(workspaces).update(archived);
        verify(docker).remove(orphan.name());
        assertEquals(1, reconciler.health().archivedWorkspaces());
        assertEquals(1, reconciler.health().removedSandboxOrphans());
    }

    @Test
    void reportsCapacityWhenAnyBoundedBatchIsFull() {
        CodingWorkspaceStartupProperties properties = new CodingWorkspaceStartupProperties();
        properties.setRecoveryBatchSize(1);
        ExecutionWorkspace workspace = recovering(ExecutionWorkspaceStatus.PROVISIONING);
        policy(workspace);
        when(workspaces.findRecoveringForUpdate(ORGANIZATION, ENVIRONMENT, 1))
                .thenReturn(List.of(workspace));
        when(docker.inspect(any())).thenReturn(Optional.empty());

        CodingWorkspaceStartupReconciler reconciler = reconciler(properties);
        reconciler.reconcile();

        assertTrue(reconciler.health().capacityLimited());
    }

    @Test
    void operationalReconcileFencesExpiredTaskOwnershipBeforePhysicalRepair() {
        CodingWorkspaceStartupReconciler reconciler =
                reconciler(new CodingWorkspaceStartupProperties());

        reconciler.reconcileWorkspaceResources();

        verify(tasks).reconcile();
        verify(workspaces).findRecoveringForUpdate(ORGANIZATION, ENVIRONMENT, 100);
    }

    private CodingWorkspaceStartupReconciler reconciler(
            CodingWorkspaceStartupProperties properties) {
        return new CodingWorkspaceStartupReconciler(
                tasks,
                workspaces,
                policies,
                worktrees,
                diffs,
                docker,
                artifacts,
                transactions,
                time,
                registration,
                properties);
    }

    private ExecutionWorkspace recovering(ExecutionWorkspaceStatus target) {
        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        WorkItemScope scope = scope();
        TaskExecutionId executionId = TaskExecutionId.generate();
        when(workspace.scope()).thenReturn(scope);
        when(workspace.taskExecutionId()).thenReturn(executionId);
        when(workspace.workspaceKey()).thenReturn(new ExecutionWorkspaceKey(
                "ws-00000000-0000-0000-0000-" + executionId.toString().replace("-", "").substring(0, 12) + "-a1"));
        when(workspace.status()).thenReturn(ExecutionWorkspaceStatus.RECOVERING);
        when(workspace.recoveryTargetStatus()).thenReturn(Optional.of(target));
        when(workspace.version()).thenReturn(7L);
        return workspace;
    }

    private ExecutionWorkspace terminal() {
        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        when(workspace.scope()).thenReturn(scope());
        TaskExecutionId executionId = TaskExecutionId.generate();
        when(workspace.taskExecutionId()).thenReturn(executionId);
        when(workspace.workspaceKey()).thenReturn(new ExecutionWorkspaceKey(
                "ws-00000000-0000-0000-0000-" + executionId.toString().replace("-", "").substring(0, 12) + "-a1"));
        when(workspace.status()).thenReturn(ExecutionWorkspaceStatus.COMPLETED);
        when(workspace.version()).thenReturn(8L);
        return workspace;
    }

    private WorkspacePolicy policy(ExecutionWorkspace workspace) {
        WorkspacePolicy policy = mock(WorkspacePolicy.class);
        WorkItemScope scope = workspace.scope();
        when(policies.findByTaskExecution(
                        scope.organizationId(),
                        scope.teamId(),
                        scope.projectId(),
                        workspace.taskExecutionId()))
                .thenReturn(Optional.of(policy));
        return policy;
    }

    private static WorkItemScope scope() {
        return new WorkItemScope(
                ORGANIZATION,
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.generate());
    }
}
