package io.crewscope.infrastructure.workspace.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.crewscope.infrastructure.workspace.git.GitCommandConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Verifies Worker-only M4-I03 Spring wiring and fail-closed root configuration. */
class ManagedWorktreeConfigurationTest {

    @TempDir Path temporaryDirectory;

    @Test
    void createsLifecycleBeansFromCanonicalRoots() throws Exception {
        Path repositoryRoot = Files.createDirectory(temporaryDirectory.resolve("repositories"));
        Path worktreeRoot = Files.createDirectory(temporaryDirectory.resolve("worktrees"));
        Path lockRoot = Files.createDirectory(temporaryDirectory.resolve("locks"));
        String owner = Files.getOwner(worktreeRoot).getName();

        runner()
                .withPropertyValues(
                        "crewscope.coding.git.command-home="
                                + temporaryDirectory.resolve("git-home"),
                        "crewscope.coding.repository.managed-root=" + repositoryRoot,
                        "crewscope.coding.repository.required-owner=" + owner,
                        "crewscope.coding.worktree.root=" + worktreeRoot,
                        "crewscope.coding.worktree.lock-root=" + lockRoot,
                        "crewscope.coding.worktree.required-owner=" + owner)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(WorkspacePathLockManager.class)
                        .hasSingleBean(WorktreeProvisioner.class));
    }

    @Test
    void rejectsMissingWorktreeAndLockRoots() throws Exception {
        Path repositoryRoot = Files.createDirectory(temporaryDirectory.resolve("repositories"));
        String owner = Files.getOwner(repositoryRoot).getName();

        runner()
                .withPropertyValues(
                        "crewscope.coding.git.command-home="
                                + temporaryDirectory.resolve("git-home"),
                        "crewscope.coding.repository.managed-root=" + repositoryRoot,
                        "crewscope.coding.repository.required-owner=" + owner,
                        "crewscope.coding.worktree.root="
                                + temporaryDirectory.resolve("missing-worktrees"),
                        "crewscope.coding.worktree.lock-root="
                                + temporaryDirectory.resolve("missing-locks"),
                        "crewscope.coding.worktree.required-owner=" + owner)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(WorktreeOperationException.class));
    }

    @Test
    void rejectsUnexpectedWorkerOwnerWithoutDisclosingRoots() throws Exception {
        Path repositoryRoot = Files.createDirectory(temporaryDirectory.resolve("repositories"));
        Path worktreeRoot = Files.createDirectory(temporaryDirectory.resolve("worktrees"));
        Path lockRoot = Files.createDirectory(temporaryDirectory.resolve("locks"));
        String owner = Files.getOwner(repositoryRoot).getName();

        runner()
                .withPropertyValues(
                        "crewscope.coding.git.command-home="
                                + temporaryDirectory.resolve("git-home"),
                        "crewscope.coding.repository.managed-root=" + repositoryRoot,
                        "crewscope.coding.repository.required-owner=" + owner,
                        "crewscope.coding.worktree.root=" + worktreeRoot,
                        "crewscope.coding.worktree.lock-root=" + lockRoot,
                        "crewscope.coding.worktree.required-owner=unexpected-owner")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable rootCause = context.getStartupFailure();
                    while (rootCause != null && rootCause.getCause() != null) {
                        rootCause = rootCause.getCause();
                    }
                    assertThat(rootCause).isInstanceOf(WorktreeOperationException.class);
                    assertThat(rootCause.getMessage())
                            .doesNotContain(worktreeRoot.toString())
                            .doesNotContain(lockRoot.toString());
                });
    }

    @Test
    void pureServerProfileDoesNotCreateHostWorktreeBeans() {
        runner()
                .withPropertyValues(
                        "crewscope.runtime.execution-profile=server",
                        "crewscope.coding.git.command-home="
                                + temporaryDirectory.resolve("server-git-home"))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(ManagedRepositoryResolver.class)
                        .doesNotHaveBean(WorkspacePathLockManager.class)
                        .doesNotHaveBean(WorktreeProvisioner.class));
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(
                GitCommandConfiguration.class,
                ManagedRepositoryConfiguration.class,
                ManagedWorktreeConfiguration.class);
    }
}
