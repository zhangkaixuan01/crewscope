package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactPurgeRequest;
import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.CodingTaskTimelinePublisher;
import io.crewscope.application.coding.WorkspacePolicyRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceFailure;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import io.crewscope.infrastructure.runtime.TaskWorkerStartupReconciler;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Completes M4 physical resource reconciliation after M3 has fenced expired Task ownership.
 *
 * <p>This decorator runs synchronously before the Worker claim loop starts. It is deliberately not
 * a shutdown hook, so graceful Drain never archives or deletes an in-flight Workspace.
 */
public final class CodingWorkspaceStartupReconciler implements TaskWorkerStartupReconciler {

    private static final ExecutionWorkspaceFailure RECOVERY_FAILURE =
            new ExecutionWorkspaceFailure("STARTUP_RECOVERY_FAILED");

    private final TaskWorkerStartupReconciler taskReconciler;
    private final ExecutionWorkspaceRepository workspaces;
    private final WorkspacePolicyRepository policies;
    private final WorktreeProvisioner worktrees;
    private final WorkspaceDiffMonitorFactory diffMonitors;
    private final CodingSandboxOrphanCleaner sandboxes;
    private final CodingArtifactLifecycle artifacts;
    private final TransactionExecutor transactions;
    private final AuthoritativeTimeProvider timeProvider;
    private final RuntimeWorkerRegistrationSpec registration;
    private final int recoveryBatchSize;
    private final int retentionBatchSize;
    private final int artifactPurgeBatchSize;
    private final AtomicReference<CodingWorkspaceStartupHealth> health =
            new AtomicReference<>(CodingWorkspaceStartupHealth.pending());
    private final CodingTaskTimelinePublisher timeline;

    CodingWorkspaceStartupReconciler(
            TaskWorkerStartupReconciler taskReconciler,
            ExecutionWorkspaceRepository workspaces,
            WorkspacePolicyRepository policies,
            WorktreeProvisioner worktrees,
            WorkspaceDiffMonitorFactory diffMonitors,
            DockerSandboxControl docker,
            CodingArtifactLifecycle artifacts,
            TransactionExecutor transactions,
            AuthoritativeTimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec registration,
            CodingWorkspaceStartupProperties properties) {
        this(
                taskReconciler,
                workspaces,
                policies,
                worktrees,
                diffMonitors,
                docker,
                artifacts,
                transactions,
                timeProvider,
                registration,
                properties,
                CodingTaskTimelinePublisher.NO_OP);
    }

    CodingWorkspaceStartupReconciler(
            TaskWorkerStartupReconciler taskReconciler,
            ExecutionWorkspaceRepository workspaces,
            WorkspacePolicyRepository policies,
            WorktreeProvisioner worktrees,
            WorkspaceDiffMonitorFactory diffMonitors,
            DockerSandboxControl docker,
            CodingArtifactLifecycle artifacts,
            TransactionExecutor transactions,
            AuthoritativeTimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec registration,
            CodingWorkspaceStartupProperties properties,
            CodingTaskTimelinePublisher timeline) {
        this.taskReconciler = Objects.requireNonNull(taskReconciler, "taskReconciler");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.worktrees = Objects.requireNonNull(worktrees, "worktrees");
        this.diffMonitors = Objects.requireNonNull(diffMonitors, "diffMonitors");
        this.sandboxes = new CodingSandboxOrphanCleaner(
                Objects.requireNonNull(docker, "docker"), workspaces);
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.registration = Objects.requireNonNull(registration, "registration");
        CodingWorkspaceStartupProperties configured = Objects.requireNonNull(properties, "properties");
        this.recoveryBatchSize = configured.requiredRecoveryBatchSize();
        this.retentionBatchSize = configured.requiredRetentionBatchSize();
        this.artifactPurgeBatchSize = configured.requiredArtifactPurgeBatchSize();
        this.timeline = Objects.requireNonNull(timeline, "timeline");
    }

    @Override
    public synchronized int reconcile() {
        try {
            int taskExecutions = taskReconciler.reconcile();
            UtcTimestamp now = authoritativeNow();
            RecoveryOutcome recovery = transactions.required(() -> recover(now));
            int unknownSandboxes = sandboxes.closeUnknown(
                    registration.organizationId(), registration.environment());
            ArchiveOutcome archive = transactions.required(() -> archive(now));
            int purged = artifacts.purge(new ArtifactPurgeRequest(now, artifactPurgeBatchSize)).size();
            boolean limited = recovery.examined() == recoveryBatchSize
                    || archive.examined() == retentionBatchSize
                    || purged == artifactPurgeBatchSize;
            health.set(new CodingWorkspaceStartupHealth(
                    true,
                    recovery.recovered(),
                    recovery.failed(),
                    archive.archived(),
                    archive.failed(),
                    recovery.removedSandboxes() + unknownSandboxes,
                    purged,
                    limited,
                    Optional.empty()));
            return taskExecutions;
        } catch (RuntimeException failure) {
            CodingWorkspaceStartupHealth previous = health.get();
            health.set(new CodingWorkspaceStartupHealth(
                    false,
                    previous.recoveredWorkspaces(),
                    previous.failedWorkspaces(),
                    previous.archivedWorkspaces(),
                    previous.archiveFailures(),
                    previous.removedSandboxOrphans(),
                    previous.purgedArtifacts(),
                    previous.capacityLimited(),
                    Optional.of(failure.getClass().getSimpleName())));
            throw failure;
        }
    }

    public CodingWorkspaceStartupHealth health() {
        return health.get();
    }

    /** Runs the same bounded Workspace and Sandbox recovery used during Worker startup. */
    public synchronized CodingWorkspaceStartupHealth reconcileWorkspaceResources() {
        try {
            // Expired Task ownership must be fenced and marked RECOVERING before physical repair.
            taskReconciler.reconcile();
            UtcTimestamp now = authoritativeNow();
            RecoveryOutcome recovery = transactions.required(() -> recover(now));
            int unknownSandboxes = sandboxes.closeUnknown(
                    registration.organizationId(), registration.environment());
            CodingWorkspaceStartupHealth previous = health.get();
            CodingWorkspaceStartupHealth updated = new CodingWorkspaceStartupHealth(
                    true,
                    recovery.recovered(),
                    recovery.failed(),
                    previous.archivedWorkspaces(),
                    previous.archiveFailures(),
                    recovery.removedSandboxes() + unknownSandboxes,
                    previous.purgedArtifacts(),
                    recovery.examined() == recoveryBatchSize,
                    Optional.empty());
            health.set(updated);
            return updated;
        } catch (RuntimeException failure) {
            recordFailure(failure);
            throw failure;
        }
    }

    /** Runs the same bounded retention Archive and Artifact purge used during Worker startup. */
    public synchronized CodingWorkspaceStartupHealth archiveWorkspaceResources() {
        try {
            UtcTimestamp now = authoritativeNow();
            ArchiveOutcome archive = transactions.required(() -> archive(now));
            int purged = artifacts.purge(
                    new ArtifactPurgeRequest(now, artifactPurgeBatchSize)).size();
            CodingWorkspaceStartupHealth previous = health.get();
            CodingWorkspaceStartupHealth updated = new CodingWorkspaceStartupHealth(
                    true,
                    previous.recoveredWorkspaces(),
                    previous.failedWorkspaces(),
                    archive.archived(),
                    archive.failed(),
                    previous.removedSandboxOrphans(),
                    purged,
                    archive.examined() == retentionBatchSize
                            || purged == artifactPurgeBatchSize,
                    Optional.empty());
            health.set(updated);
            return updated;
        } catch (RuntimeException failure) {
            recordFailure(failure);
            throw failure;
        }
    }

    private void recordFailure(RuntimeException failure) {
        CodingWorkspaceStartupHealth previous = health.get();
        health.set(new CodingWorkspaceStartupHealth(
                false,
                previous.recoveredWorkspaces(),
                previous.failedWorkspaces(),
                previous.archivedWorkspaces(),
                previous.archiveFailures(),
                previous.removedSandboxOrphans(),
                previous.purgedArtifacts(),
                previous.capacityLimited(),
                Optional.of(failure.getClass().getSimpleName())));
    }

    private RecoveryOutcome recover(UtcTimestamp now) {
        List<ExecutionWorkspace> candidates = workspaces.findRecoveringForUpdate(
                registration.organizationId(), registration.environment(), recoveryBatchSize);
        int recovered = 0;
        int failed = 0;
        int removedSandboxes = 0;
        for (ExecutionWorkspace workspace : candidates) {
            try {
                if (sandboxes.closeKnown(workspace)) {
                    removedSandboxes++;
                }
                WorkspacePolicy policy = policy(workspace);
                ExecutionWorkspaceStatus target = workspace.recoveryTargetStatus().orElseThrow();
                if (target == ExecutionWorkspaceStatus.PROVISIONING) {
                    worktrees.rollbackProvisionOrphan(workspace, policy);
                } else {
                    ManagedWorktree worktree = target == ExecutionWorkspaceStatus.FINALIZING
                            ? worktrees.recoverFinalizing(workspace, policy)
                            : worktrees.verify(workspace, policy);
                    diffMonitors.reconcileOnce(workspace, worktree, policy);
                }
                recovered++;
            } catch (RuntimeException failure) {
                updateWorkspace(workspace.fail(
                        RECOVERY_FAILURE,
                        workspace.version(),
                        registration.actor(),
                        now));
                failed++;
            }
        }
        return new RecoveryOutcome(candidates.size(), recovered, failed, removedSandboxes);
    }

    private ArchiveOutcome archive(UtcTimestamp now) {
        List<ExecutionWorkspace> candidates = workspaces.findRetentionDueForUpdate(
                registration.organizationId(),
                registration.environment(),
                now,
                retentionBatchSize);
        int archived = 0;
        int failed = 0;
        for (ExecutionWorkspace workspace : candidates) {
            try {
                sandboxes.closeKnown(workspace);
                worktrees.archive(workspace, policy(workspace));
                updateWorkspace(workspace.archive(
                        now, workspace.version(), registration.actor()));
                archived++;
            } catch (RuntimeException failure) {
                // The terminal durable fact remains retryable; physical coordinates stay private.
                failed++;
            }
        }
        return new ArchiveOutcome(candidates.size(), archived, failed);
    }

    private WorkspacePolicy policy(ExecutionWorkspace workspace) {
        return policies.findByTaskExecution(
                        workspace.scope().organizationId(),
                        workspace.scope().teamId(),
                        workspace.scope().projectId(),
                        workspace.taskExecutionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Workspace Policy is unavailable during startup reconciliation"));
    }

    private ExecutionWorkspace updateWorkspace(ExecutionWorkspace changed) {
        ExecutionWorkspace committed = workspaces.update(changed);
        timeline.workspaceChanged(committed);
        return committed;
    }

    /** Keeps the database-backed clock inside its mandatory transaction boundary. */
    private UtcTimestamp authoritativeNow() {
        return transactions.required(timeProvider::now);
    }

    private record RecoveryOutcome(
            int examined, int recovered, int failed, int removedSandboxes) {}

    private record ArchiveOutcome(int examined, int archived, int failed) {}
}
