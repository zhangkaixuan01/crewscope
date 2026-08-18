package io.crewscope.domain.coding;

import java.util.Objects;

/** Path-independent locator resolved below the configured trusted Worktree Root by M4-I03. */
public record ManagedWorktreeLocator(
        RepositoryKey repositoryKey, ExecutionWorkspaceKey workspaceKey) {

    public ManagedWorktreeLocator {
        repositoryKey = Objects.requireNonNull(repositoryKey, "repositoryKey");
        workspaceKey = Objects.requireNonNull(workspaceKey, "workspaceKey");
    }

    public String relativeValue() {
        return repositoryKey.value() + "/" + workspaceKey.value();
    }
}
