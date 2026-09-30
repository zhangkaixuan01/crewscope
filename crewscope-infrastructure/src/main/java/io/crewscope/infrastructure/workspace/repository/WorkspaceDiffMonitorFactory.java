package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.WorkspacePolicy;
import java.util.Objects;

/** Opens the initial RESET before allowing asynchronous Watcher hints to publish DELTAs. */
public final class WorkspaceDiffMonitorFactory {

    private final GitWorkspaceDiffReconciler reconciler;
    private final WorkspaceDiffEventStore events;
    private final WorkspaceDiffWatcherFactory watchers;

    WorkspaceDiffMonitorFactory(
            GitWorkspaceDiffReconciler reconciler,
            WorkspaceDiffEventStore events,
            WorkspaceDiffWatcherFactory watchers) {
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.events = Objects.requireNonNull(events, "events");
        this.watchers = Objects.requireNonNull(watchers, "watchers");
    }

    public WorkspaceDiffMonitor open(
            ExecutionWorkspace workspace, ManagedWorktree worktree, WorkspacePolicy policy) {
        WorkspaceDiffMonitor monitor = new WorkspaceDiffMonitor(
                workspace, worktree, policy, reconciler, events);
        initializeWithRetry(monitor);
        try {
            WorkspaceDiffWatcher watcher = watchers.open(
                    workspace, worktree, policy, monitor::acceptHint);
            monitor.attach(watcher);
            return monitor;
        } catch (RuntimeException failure) {
            monitor.close();
            throw failure;
        }
    }

    /**
     * Retries the initial RESET for a bounded time when Git reports a transient command failure.
     * A freshly provisioned Worktree can still surface a sub-second "not a repository" window on
     * shared filesystems before its metadata is fully visible; the RESET is an idempotent
     * recomputation, so a short retry converges instead of failing the whole execution.
     * Deterministic findings (policy, budget, output shape) fail immediately.
     */
    private static void initializeWithRetry(WorkspaceDiffMonitor monitor) {
        for (int attempt = 0; ; attempt++) {
            try {
                monitor.initialize();
                return;
            } catch (WorkspaceDiffException failure) {
                if (attempt >= 3 || failure.error() != WorkspaceDiffError.COMMAND_FAILED) {
                    throw failure;
                }
                sleepQuietly(750);
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Workspace Diff initialization was interrupted", interrupted);
        }
    }

    /** Rebuilds one authoritative RESET without leaving a Watcher thread running at startup. */
    public WorkspaceDiffSnapshot reconcileOnce(
            ExecutionWorkspace workspace, ManagedWorktree worktree, WorkspacePolicy policy) {
        WorkspaceDiffSnapshot snapshot = reconciler.reconcileWorkingTree(
                Objects.requireNonNull(workspace, "workspace"),
                Objects.requireNonNull(worktree, "worktree"),
                Objects.requireNonNull(policy, "policy"),
                java.util.Optional.empty());
        events.restart(WorkspaceDiffStreamKey.from(workspace), snapshot.manifest());
        return snapshot;
    }
}
