package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.coding.BuildProfileCatalog;
import io.crewscope.application.coding.CodingTaskTimelinePublisher;
import io.crewscope.application.coding.CodingTargetSnapshotRepository;
import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.WorkspacePolicyOverlayRepository;
import io.crewscope.application.coding.WorkspacePolicyRepository;
import io.crewscope.application.execution.TaskExecutionTerminalStatus;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceCompletionReason;
import io.crewscope.domain.coding.ExecutionWorkspaceFailure;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceRetention;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.SandboxNetworkMode;
import io.crewscope.domain.coding.SandboxResourceBudget;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.coding.WorkspacePolicyOverlay;
import io.crewscope.domain.coding.WorkspacePolicyOverlayId;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.identity.Principal;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import java.util.Objects;
import java.util.Optional;

/** Durable PREPARING/RUNNING/terminal orchestration for managed Coding resources. */
public final class DurableCodingWorkspaceExecutionLifecycle
        implements CodingWorkspaceExecutionLifecycle {

    private static final ExecutionWorkspaceFailure EXECUTION_FAILED =
            new ExecutionWorkspaceFailure("TASK_EXECUTION_FAILED");

    private final CodingTargetSnapshotRepository targets;
    private final ExecutionWorkspaceRepository workspaces;
    private final WorkspacePolicyRepository policies;
    private final WorkspacePolicyOverlayRepository overlays;
    private final BuildProfileCatalog buildProfiles;
    private final ManagedRepositoryResolver repositories;
    private final WorktreeProvisioner worktrees;
    private final TaskExecutionSandboxFactory sandboxes;
    private final WorkspaceDiffMonitorFactory diffMonitors;
    private final WorkspaceDiffFinalizer diffFinalizer;
    private final CodingWorktreePreparationHook worktreePreparation;
    private final CodingWorkspaceRuntimeRegistry registry;
    private final CodingFilesystemUsageRegistry filesystemUsages;
    private final SandboxCommandUsageRegistry commandUsages;
    private final TransactionExecutor transactions;
    private final AuthoritativeTimeProvider timeProvider;
    private final RuntimeWorkerRegistrationSpec registration;
    private final PrincipalRepository principals;
    private final CodingWorkspaceExecutionProperties properties;
    private final CodingTaskTimelinePublisher timeline;

    public DurableCodingWorkspaceExecutionLifecycle(
            CodingTargetSnapshotRepository targets,
            ExecutionWorkspaceRepository workspaces,
            WorkspacePolicyRepository policies,
            WorkspacePolicyOverlayRepository overlays,
            BuildProfileCatalog buildProfiles,
            ManagedRepositoryResolver repositories,
            WorktreeProvisioner worktrees,
            TaskExecutionSandboxFactory sandboxes,
            WorkspaceDiffMonitorFactory diffMonitors,
            WorkspaceDiffFinalizer diffFinalizer,
            CodingWorktreePreparationHook worktreePreparation,
            CodingWorkspaceRuntimeRegistry registry,
            CodingFilesystemUsageRegistry filesystemUsages,
            SandboxCommandUsageRegistry commandUsages,
            TransactionExecutor transactions,
            AuthoritativeTimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec registration,
            PrincipalRepository principals,
            CodingWorkspaceExecutionProperties properties,
            CodingTaskTimelinePublisher timeline) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.overlays = Objects.requireNonNull(overlays, "overlays");
        this.buildProfiles = Objects.requireNonNull(buildProfiles, "buildProfiles");
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.worktrees = Objects.requireNonNull(worktrees, "worktrees");
        this.sandboxes = Objects.requireNonNull(sandboxes, "sandboxes");
        this.diffMonitors = Objects.requireNonNull(diffMonitors, "diffMonitors");
        this.diffFinalizer = Objects.requireNonNull(diffFinalizer, "diffFinalizer");
        this.worktreePreparation = Objects.requireNonNull(
                worktreePreparation, "worktreePreparation");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.filesystemUsages = Objects.requireNonNull(filesystemUsages, "filesystemUsages");
        this.commandUsages = Objects.requireNonNull(commandUsages, "commandUsages");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.timeline = Objects.requireNonNull(timeline, "timeline");
    }

    @Override
    public Optional<CodingWorkspaceExecution> prepare(
            TaskExecution preparing, ExecutionLease lease, PolicySnapshot policySnapshot) {
        var scope = preparing.scope();
        var target = targets.findLatestByTask(
                        scope.organizationId(), scope.teamId(), scope.projectId(), preparing.taskId())
                .orElse(null);
        if (target == null) {
            return Optional.empty();
        }
        var profile = buildProfiles.findExact(target.buildProfile())
                .orElseThrow(() -> new IllegalStateException(
                        "Pinned Coding BuildProfile is unavailable on this Worker"));
        WorkspacePolicy workspacePolicy = transactions.required(() -> policies
                .findByTaskExecution(
                        scope.organizationId(), scope.teamId(), scope.projectId(), preparing.id())
                .orElseGet(() -> createPolicy(
                        target, preparing, policySnapshot, profile)));
        ExecutionWorkspace workspace = transactions.required(() -> prepareDurableWorkspace(
                target, preparing, lease, workspacePolicy));
        ManagedWorktree worktree;
        try {
            worktree = workspace.status() == ExecutionWorkspaceStatus.PROVISIONING
                    ? worktrees.provision(workspace, workspacePolicy)
                    : workspace.status() == ExecutionWorkspaceStatus.FINALIZING
                            ? worktrees.recoverFinalizing(workspace, workspacePolicy)
                            : worktrees.verify(workspace, workspacePolicy);
            ManagedRepository repository = repositories.resolve(workspace.repositoryKey());
            worktreePreparation.prepare(workspace, target, repository, worktree);
            var sandbox = sandboxes.recover(
                    workspace,
                    worktree,
                    workspacePolicy,
                    profile,
                    lease,
                    authoritativeNow());
            if (workspace.status() == ExecutionWorkspaceStatus.PROVISIONING) {
                workspace = markReady(workspace, preparing, lease);
            }
            CodingWorkspaceExecution execution = new CodingWorkspaceExecution(
                    workspace,
                    target,
                    workspacePolicy,
                    profile,
                    repository,
                    worktree,
                    sandbox);
            return Optional.of(execution);
        } catch (RuntimeException failure) {
            failPreparation(workspace, workspacePolicy);
            throw failure;
        }
    }

    @Override
    public void activate(
            CodingWorkspaceExecution execution,
            TaskExecution runningExecution,
            ExecutionLease runLease) {
        ExecutionWorkspace workspace = execution.workspace();
        if (workspace.status() == ExecutionWorkspaceStatus.READY) {
            workspace = activateWorkspace(workspace, runningExecution, runLease);
            execution.workspace(workspace);
        }
        if (workspace.status() == ExecutionWorkspaceStatus.ACTIVE
                || workspace.status() == ExecutionWorkspaceStatus.FINALIZING) {
            execution.diffMonitor(diffMonitors.open(
                    workspace, execution.worktree(), execution.policy()));
            registry.register(execution);
        }
    }

    @Override
    public void beforeRelease(
            CodingWorkspaceExecution execution,
            TaskExecution currentExecution,
            ExecutionLease lease,
            TaskExecutionTerminalStatus terminalStatus) {
        execution.closeMonitor();
        switch (terminalStatus) {
            case PAUSED, INTERRUPTED -> sandboxes.pause(
                    execution.sandbox(), execution.workspace(), lease, authoritativeNow());
            case COMPLETED -> finalizeWorkspace(
                    execution,
                    currentExecution,
                    currentExecution.status() == TaskExecutionStatus.CANCEL_REQUESTED
                            ? ExecutionWorkspaceCompletionReason.CANCELLED
                            : ExecutionWorkspaceCompletionReason.SUCCEEDED);
            case CANCELED -> finalizeWorkspace(
                    execution, currentExecution, ExecutionWorkspaceCompletionReason.CANCELLED);
            case FAILED -> sandboxes.destroy(execution.sandbox(), execution.workspace());
        }
    }

    @Override
    public void afterRelease(
            CodingWorkspaceExecution execution,
            TaskExecution terminalExecution,
            TaskExecutionTerminalStatus terminalStatus) {
        ExecutionWorkspace workspace = execution.workspace();
        switch (terminalExecution.status()) {
            case COMPLETED, CANCELLED -> {
                if (workspace.status() == ExecutionWorkspaceStatus.FINALIZING) {
                    workspace = completeFinalizing(workspace, terminalExecution);
                }
            }
            case PAUSED -> workspace = preserveForPause(workspace, terminalExecution);
            case WAITING -> workspace = preserveForWait(workspace, terminalExecution);
            case FAILED -> workspace = failExecution(workspace);
            default -> throw new IllegalStateException(
                    "Released Coding execution did not reach a durable boundary");
        }
        execution.workspace(workspace);
        if (terminalExecution.status().isTerminal()) {
            filesystemUsages.forget(workspace.workspaceKey());
            commandUsages.forget(workspace.workspaceKey());
        }
        registry.forget(execution);
    }

    @Override
    public void abandon(CodingWorkspaceExecution execution) {
        execution.closeMonitor();
        registry.forget(execution);
    }

    private WorkspacePolicy createPolicy(
            io.crewscope.domain.coding.CodingTargetSnapshot target,
            TaskExecution execution,
            PolicySnapshot policySnapshot,
            io.crewscope.domain.coding.BuildProfile profile) {
        WorkspacePolicy created = policies.create(WorkspacePolicy.create(
                WorkspacePolicyId.generate(),
                target,
                execution,
                policySnapshot,
                profile,
                AllowedPathSet.from(target.allowedPaths()),
                sandboxBudget(),
                operationBudget(),
                registration.actor(),
                timeProvider.now()));
        overlays.create(WorkspacePolicyOverlay.unrestricted(
                WorkspacePolicyOverlayId.generate(),
                created,
                registration.actor(),
                timeProvider.now()));
        return created;
    }

    private ExecutionWorkspace prepareDurableWorkspace(
            io.crewscope.domain.coding.CodingTargetSnapshot target,
            TaskExecution execution,
            ExecutionLease lease,
            WorkspacePolicy policy) {
        var scope = execution.scope();
        Optional<ExecutionWorkspace> existing = workspaces.findByTaskExecutionForUpdate(
                scope.organizationId(), scope.teamId(), scope.projectId(), execution.id());
        if (existing.isEmpty()) {
            ExecutionWorkspace allocated = workspaces.create(ExecutionWorkspace.allocate(
                    ExecutionWorkspaceId.generate(),
                    target,
                    execution,
                    lease,
                    new ExecutionWorkspaceRetention(io.crewscope.domain.shared.time.UtcTimestamp.from(
                            timeProvider.now().value().plus(properties.requiredRetention()))),
                    registration.actor(),
                    timeProvider.now()));
            return updateWorkspace(allocated.beginProvisioning(
                    execution,
                    lease,
                    allocated.version(),
                    registration.actor(),
                    timeProvider.now()));
        }
        ExecutionWorkspace workspace = existing.orElseThrow();
        if (workspace.status() == ExecutionWorkspaceStatus.RECOVERING) {
            return updateWorkspace(workspace.resumeRecovery(
                    execution,
                    lease,
                    workspace.version(),
                    registration.actor(),
                    timeProvider.now()));
        }
        if (workspace.status() == ExecutionWorkspaceStatus.READY) {
            return updateWorkspace(workspace.rebindForResume(
                    execution,
                    lease,
                    workspace.version(),
                    registration.actor(),
                    timeProvider.now()));
        }
        throw new IllegalStateException(
                "Coding Workspace cannot be prepared from " + workspace.status());
    }

    private ExecutionWorkspace markReady(
            ExecutionWorkspace workspace, TaskExecution execution, ExecutionLease lease) {
        return transactions.required(() -> updateWorkspace(workspace.markReady(
                execution,
                lease,
                workspace.version(),
                registration.actor(),
                timeProvider.now())));
    }

    private ExecutionWorkspace activateWorkspace(
            ExecutionWorkspace workspace, TaskExecution execution, ExecutionLease lease) {
        return transactions.required(() -> updateWorkspace(workspace.activate(
                execution,
                lease,
                workspace.version(),
                registration.actor(),
                timeProvider.now())));
    }

    private ExecutionWorkspace completeFinalizing(
            ExecutionWorkspace workspace, TaskExecution execution) {
        return transactions.required(() -> updateWorkspace(workspace.completeFinalizing(
                execution,
                workspace.version(),
                registration.actor(),
                timeProvider.now())));
    }

    private ExecutionWorkspace preserveForPause(
            ExecutionWorkspace workspace, TaskExecution execution) {
        return transactions.required(() -> updateWorkspace(workspace.preserveForPause(
                execution,
                workspace.version(),
                registration.actor(),
                timeProvider.now())));
    }

    private ExecutionWorkspace preserveForWait(
            ExecutionWorkspace workspace, TaskExecution execution) {
        return transactions.required(() -> updateWorkspace(workspace.preserveForWait(
                execution,
                workspace.version(),
                registration.actor(),
                timeProvider.now())));
    }

    private ExecutionWorkspace failExecution(ExecutionWorkspace workspace) {
        return transactions.required(() -> updateWorkspace(workspace.fail(
                EXECUTION_FAILED,
                workspace.version(),
                registration.actor(),
                timeProvider.now())));
    }

    private void finalizeWorkspace(
            CodingWorkspaceExecution execution,
            TaskExecution taskExecution,
            ExecutionWorkspaceCompletionReason reason) {
        ExecutionWorkspace workspace = execution.workspace();
        if (workspace.status() != ExecutionWorkspaceStatus.FINALIZING) {
            workspace = beginFinalizing(workspace, taskExecution, reason);
            execution.workspace(workspace);
        }
        if (execution.finalDiff().isPresent()) {
            return;
        }
        sandboxes.destroy(execution.sandbox(), workspace);
        WorktreeArchiveResult archive = worktrees.archive(workspace, execution.policy());
        execution.finalDiff(diffFinalizer.finalizeDiff(
                workspace,
                execution.target(),
                execution.policy(),
                archive,
                executionPrincipal(taskExecution),
                execution.lastLiveManifest()));
    }

    private Principal executionPrincipal(TaskExecution execution) {
        var principalId = execution.planningContext()
                .orElseThrow(() -> new IllegalStateException(
                        "Coding TaskExecution has no pinned execution Principal"))
                .executionPrincipal()
                .principalId();
        return principals.findById(execution.scope().organizationId(), principalId)
                .filter(Principal::canAct)
                .orElseThrow(() -> new IllegalStateException(
                        "Coding TaskExecution Principal is unavailable"));
    }

    private ExecutionWorkspace beginFinalizing(
            ExecutionWorkspace workspace,
            TaskExecution execution,
            ExecutionWorkspaceCompletionReason reason) {
        return transactions.required(() -> updateWorkspace(workspace.beginFinalizing(
                reason,
                execution,
                workspace.version(),
                registration.actor(),
                timeProvider.now())));
    }

    private void failPreparation(ExecutionWorkspace workspace, WorkspacePolicy policy) {
        try {
            if (workspace.status() == ExecutionWorkspaceStatus.PROVISIONING) {
                worktrees.rollbackProvisionOrphan(workspace, policy);
            }
        } catch (RuntimeException ignored) {
            // Durable PROVISIONING remains recoverable when compensation cannot prove ownership.
        }
    }

    private ExecutionWorkspace updateWorkspace(ExecutionWorkspace changed) {
        ExecutionWorkspace committed = workspaces.update(changed);
        timeline.workspaceChanged(committed);
        return committed;
    }

    /** Reads database time in its required transaction without enclosing filesystem work. */
    private io.crewscope.domain.shared.time.UtcTimestamp authoritativeNow() {
        return transactions.required(timeProvider::now);
    }

    private SandboxResourceBudget sandboxBudget() {
        return new SandboxResourceBudget(
                SandboxNetworkMode.NONE,
                properties.getCpuCount(),
                properties.getMemoryMib(),
                properties.getPids(),
                properties.getMaxCommandDurationSeconds(),
                properties.getMaxCommandOutputBytes(),
                true);
    }

    private WorkspaceOperationBudget operationBudget() {
        return new WorkspaceOperationBudget(
                properties.getMaxCommandCalls(),
                properties.getMaxChangedFiles(),
                properties.getMaxSingleFileBytes(),
                properties.getMaxWriteOperations(),
                properties.getMaxWrittenBytes(),
                properties.getMaxDiffBytes(),
                properties.getMaxTestRepairRounds());
    }
}
