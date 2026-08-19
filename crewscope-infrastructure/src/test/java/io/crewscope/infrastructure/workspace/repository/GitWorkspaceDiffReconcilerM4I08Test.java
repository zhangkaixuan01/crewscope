package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.DiffFileKind;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.git.GitCommandPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-Git M4-I08 coverage for authority parsing, untracked files and policy limits. */
class GitWorkspaceDiffReconcilerM4I08Test {

    @TempDir Path temporaryDirectory;

    private Path repository;
    private GitWorkspaceDiffReconciler reconciler;
    private RepositoryCommitId baseline;

    @BeforeEach
    void setUp() throws Exception {
        repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
        run("git", "init", "--initial-branch=main", repository.toString());
        run("git", "-C", repository.toString(), "config", "user.name", "CrewScope Diff Test");
        run("git", "-C", repository.toString(), "config", "user.email", "diff@crewscope.local");
        Files.createDirectories(repository.resolve("src"));
        Files.writeString(repository.resolve("README.md"), "before\n", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("obsolete.txt"), "obsolete\n", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("src/App.java"), "class App {}\n", StandardCharsets.UTF_8);
        run("git", "-C", repository.toString(), "add", "--all");
        run("git", "-C", repository.toString(), "commit", "-m", "baseline");
        baseline = new RepositoryCommitId(run(
                        "git", "-C", repository.toString(), "rev-parse", "HEAD")
                .strip());
        WorkspaceDiffProperties properties = properties();
        reconciler = new GitWorkspaceDiffReconciler(
                new GitCommandExecutor(new GitCommandPolicy(
                        temporaryDirectory.resolve("git-home"),
                        Duration.ofSeconds(15),
                        4 * 1024 * 1024)),
                properties);
    }

    @Test
    void reconcilesTrackedUntrackedDeletedAndNoOpGenerations() throws Exception {
        Files.writeString(repository.resolve("README.md"), "after\n", StandardCharsets.UTF_8);
        Files.delete(repository.resolve("obsolete.txt"));
        Files.writeString(repository.resolve("src/New.java"), "class New {}\n", StandardCharsets.UTF_8);

        WorkspaceDiffSnapshot first = reconciler.reconcile(
                repository, baseline, Optional.empty(), policy("."), Optional.empty(), true);
        assertEquals(List.of("README.md", "obsolete.txt", "src/New.java"), first.manifest()
                .files()
                .stream()
                .map(file -> file.path().value())
                .toList());
        assertEquals(DiffFileKind.MODIFIED, first.manifest().files().get(0).kind());
        assertEquals(DiffFileKind.DELETED, first.manifest().files().get(1).kind());
        assertEquals(DiffFileKind.ADDED, first.manifest().files().get(2).kind());
        assertTrue(first.fullPatch().contains("src/New.java"));

        WorkspaceDiffSnapshot unchanged = reconciler.reconcile(
                repository,
                baseline,
                Optional.empty(),
                policy("."),
                Optional.of(first.manifest()),
                true);
        assertEquals(first.manifest().generation(), unchanged.manifest().generation());
        assertEquals(first.manifest().contentHash(), unchanged.manifest().contentHash());

        Files.writeString(repository.resolve("src/App.java"), "class App { int v; }\n");
        WorkspaceDiffSnapshot next = reconciler.reconcile(
                repository,
                baseline,
                Optional.empty(),
                policy("."),
                Optional.of(first.manifest()),
                true);
        assertEquals(2, next.manifest().generation().value());
    }

    @Test
    void commitPairDetectsRenameAndBinaryWithoutMutableWorktreeFacts() throws Exception {
        Files.createDirectories(repository.resolve("docs"));
        Files.move(repository.resolve("README.md"), repository.resolve("docs/README.md"));
        Files.write(repository.resolve("src/logo.bin"), new byte[] {0, 1, 2, 3, 0, 5});
        run("git", "-C", repository.toString(), "add", "--all");
        run("git", "-C", repository.toString(), "commit", "-m", "delivery");
        RepositoryCommitId delivery = new RepositoryCommitId(run(
                        "git", "-C", repository.toString(), "rev-parse", "HEAD")
                .strip());

        WorkspaceDiffSnapshot snapshot = reconciler.reconcile(
                repository,
                baseline,
                Optional.of(delivery),
                policy("."),
                Optional.empty(),
                false);

        assertTrue(snapshot.manifest().files().stream().anyMatch(file ->
                file.kind() == DiffFileKind.RENAMED
                        && file.path().value().equals("docs/README.md")
                        && file.oldPath().orElseThrow().value().equals("README.md")));
        assertTrue(snapshot.manifest().files().stream().anyMatch(file ->
                file.path().value().equals("src/logo.bin")
                        && file.binary()
                        && file.patchPreview().isEmpty()));
    }

    @Test
    void rejectsOutsideAllowedPathsAndDiffBudgets() throws Exception {
        Files.writeString(repository.resolve("README.md"), "outside\n", StandardCharsets.UTF_8);
        WorkspaceDiffException outside = assertThrows(
                WorkspaceDiffException.class,
                () -> reconciler.reconcile(
                        repository,
                        baseline,
                        Optional.empty(),
                        policy("src"),
                        Optional.empty(),
                        true));
        assertEquals(WorkspaceDiffError.PATH_OUTSIDE_POLICY, outside.error());

        WorkspacePolicy limited = policy(".");
        when(limited.operationBudget()).thenReturn(
                new WorkspaceOperationBudget(5, 10, 100, 5, 100, 8, 1));
        WorkspaceDiffException exceeded = assertThrows(
                WorkspaceDiffException.class,
                () -> reconciler.reconcile(
                        repository,
                        baseline,
                        Optional.empty(),
                        limited,
                        Optional.empty(),
                        true));
        assertEquals(WorkspaceDiffError.DIFF_LIMIT_EXCEEDED, exceeded.error());
    }

    private WorkspacePolicy policy(String... roots) {
        WorkspacePolicy policy = mock(WorkspacePolicy.class);
        when(policy.allowedPaths()).thenReturn(AllowedPathSet.of(roots));
        when(policy.operationBudget()).thenReturn(
                new WorkspaceOperationBudget(20, 100, 1024 * 1024, 100, 4 * 1024 * 1024, 4 * 1024 * 1024, 3));
        return policy;
    }

    private static WorkspaceDiffProperties properties() {
        WorkspaceDiffProperties properties = new WorkspaceDiffProperties();
        properties.setPatchPreviewBytes(2_048);
        properties.setPatchPreviewLines(20);
        return properties;
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("Git fixture command failed");
        }
        return output;
    }
}
