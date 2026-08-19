package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.WorkspacePolicy;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** One Workspace lifecycle joining Watcher hints to serialized Git reconciliation and replay. */
public final class WorkspaceDiffMonitor implements AutoCloseable {

    private final ExecutionWorkspace workspace;
    private final ManagedWorktree worktree;
    private final WorkspacePolicy policy;
    private final WorkspaceDiffStreamKey streamKey;
    private final GitWorkspaceDiffReconciler reconciler;
    private final WorkspaceDiffEventStore events;
    private final ReentrantLock reconcileLock = new ReentrantLock();
    private final AtomicReference<WorkspaceDiffException> lastFailure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile WorkspaceDiffWatcher watcher;

    WorkspaceDiffMonitor(
            ExecutionWorkspace workspace,
            ManagedWorktree worktree,
            WorkspacePolicy policy,
            GitWorkspaceDiffReconciler reconciler,
            WorkspaceDiffEventStore events) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.worktree = Objects.requireNonNull(worktree, "worktree");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.streamKey = WorkspaceDiffStreamKey.from(workspace);
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.events = Objects.requireNonNull(events, "events");
    }

    void initialize() {
        WorkspaceDiffSnapshot initial = reconciler.reconcileWorkingTree(
                workspace, worktree, policy, Optional.empty());
        events.restart(streamKey, initial.manifest());
    }

    void attach(WorkspaceDiffWatcher watcher) {
        this.watcher = Objects.requireNonNull(watcher, "watcher");
    }

    void acceptHint(WorkspaceDiffHint ignored) {
        try {
            reconcileNow();
        } catch (WorkspaceDiffException failure) {
            lastFailure.set(failure);
        }
    }

    /** Serializes tool-completion, checkpoint and WatchService-triggered reconciliations. */
    public Optional<WorkspaceDiffEvent> reconcileNow() {
        if (closed.get()) {
            throw new IllegalStateException("Workspace Diff monitor is closed");
        }
        reconcileLock.lock();
        try {
            Optional<DiffManifest> previous = events.latest(streamKey);
            WorkspaceDiffSnapshot snapshot = reconciler.reconcileWorkingTree(
                    workspace, worktree, policy, previous);
            Optional<WorkspaceDiffEvent> published = events.reconcile(
                    streamKey, snapshot.manifest());
            lastFailure.set(null);
            return published;
        } finally {
            reconcileLock.unlock();
        }
    }

    public Optional<DiffManifest> latest() {
        return events.latest(streamKey);
    }

    public WorkspaceDiffReplay replay(String cursor, int limit) {
        return events.replay(streamKey, cursor, limit);
    }

    public Optional<WorkspaceDiffException> lastFailure() {
        WorkspaceDiffException monitorFailure = lastFailure.get();
        if (monitorFailure != null) {
            return Optional.of(monitorFailure);
        }
        WorkspaceDiffWatcher current = watcher;
        return current == null ? Optional.empty() : current.lastFailure();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        WorkspaceDiffWatcher current = watcher;
        if (current != null) {
            current.close();
        }
    }
}
