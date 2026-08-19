package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.WorkspacePolicy;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;

/** Creates one recursive, debounced WatchService lifecycle for a verified managed Worktree. */
public final class WorkspaceDiffWatcherFactory {

    private final WorkspaceDiffProperties properties;
    private final Clock clock;

    WorkspaceDiffWatcherFactory(WorkspaceDiffProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.properties.validate();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WorkspaceDiffWatcher open(
            ExecutionWorkspace workspace,
            ManagedWorktree worktree,
            WorkspacePolicy policy,
            Consumer<WorkspaceDiffHint> hints) {
        ExecutionWorkspace current = Objects.requireNonNull(workspace, "workspace");
        ManagedWorktree managed = Objects.requireNonNull(worktree, "worktree");
        WorkspacePolicy effective = Objects.requireNonNull(policy, "policy");
        if (!current.id().equals(managed.workspaceId())
                || !current.scope().equals(effective.scope())
                || !current.taskExecutionId().equals(effective.taskExecutionId())
                || current.attempt() != effective.attempt()
                || !current.codingTarget().equals(effective.codingTarget())
                || !current.baselineCommit().equals(managed.baselineCommit())) {
            throw new WorkspaceDiffException(
                    WorkspaceDiffError.INVALID_CONTEXT,
                    "Watcher facts do not match the managed Workspace");
        }
        try {
            return WorkspaceDiffWatcher.start(
                    WorkspaceDiffStreamKey.from(current),
                    managed.canonicalPath(),
                    effective.allowedPaths(),
                    properties,
                    clock,
                    Objects.requireNonNull(hints, "hints"));
        } catch (IOException failure) {
            throw new WorkspaceDiffException(
                    WorkspaceDiffError.WATCHER_FAILED,
                    "Workspace Diff Watcher could not be started");
        }
    }
}
