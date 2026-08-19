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
        monitor.initialize();
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
