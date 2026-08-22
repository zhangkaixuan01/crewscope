package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.ExecutionWorkspace;

/** Optional deployment hook that prepares verified, non-deliverable Worktree fixtures. */
@FunctionalInterface
public interface CodingWorktreePreparationHook {

    CodingWorktreePreparationHook NONE = (workspace, target, repository, worktree) -> {};

    void prepare(
            ExecutionWorkspace workspace,
            CodingTargetSnapshot target,
            ManagedRepository repository,
            ManagedWorktree worktree);
}
