package io.crewscope.infrastructure.workspace.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.ManagedWorkspaceBranch;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.WorkspaceArchiveReference;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskExecutionId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-process contract tests for the M4-I01 typed host Git execution boundary. */
class GitCommandExecutorM4I01IntegrationTest {

    private static final Duration HOST_COMMAND_TIMEOUT = Duration.ofSeconds(15);

    @TempDir Path temporaryDirectory;

    private Path sourceRepository;
    private Path bareRepository;
    private GitCommandExecutor executor;
    private RepositoryCommitId baselineCommit;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(commandSucceeds("git", "--version"), "Git is required");
        sourceRepository = temporaryDirectory.resolve("source");
        bareRepository = temporaryDirectory.resolve("managed.git");
        executor = new GitCommandExecutor(new GitCommandPolicy(
                temporaryDirectory.resolve("command-home"), Duration.ofSeconds(10), 1024 * 1024));

        runRequired(temporaryDirectory, "git", "init", "--initial-branch=main", sourceRepository.toString());
        runRequired(sourceRepository, "git", "config", "user.name", "Fixture");
        runRequired(sourceRepository, "git", "config", "user.email", "fixture@crewscope.local");
        Files.writeString(sourceRepository.resolve("README.md"), "baseline\n", StandardCharsets.UTF_8);
        runRequired(sourceRepository, "git", "add", "README.md");
        runRequired(sourceRepository, "git", "commit", "-m", "initial fixture");
        runRequired(
                temporaryDirectory,
                "git",
                "clone",
                "--bare",
                sourceRepository.toString(),
                bareRepository.toString());
        baselineCommit = executor.resolveBranch(bareRepository, new RepositoryBranchName("main"));
    }

    @Test
    void executesTypedResolveWorktreeStatusDiffLogShowAndCommitOperations() throws Exception {
        assertTrue(executor.isBareRepository(bareRepository));
        assertFalse(executor.isBareRepository(sourceRepository));
        executor.verifyCommit(bareRepository, baselineCommit);

        TaskExecutionId executionId = TaskExecutionId.generate();
        ManagedWorkspaceBranch branch = ManagedWorkspaceBranch.derive(executionId, 1);
        Path worktree = temporaryDirectory.resolve("worktree");

        executor.addWorktree(bareRepository, worktree, branch, baselineCommit);
        assertEquals(baselineCommit, executor.findManagedBranch(bareRepository, branch).orElseThrow());
        assertEquals(baselineCommit, executor.headCommit(worktree));
        assertTrue(executor.isCurrentBranch(worktree, branch));
        assertEquals(bareRepository.toRealPath(), executor.commonDirectory(worktree).toRealPath());
        Files.writeString(worktree.resolve("README.md"), "changed\n", StandardCharsets.UTF_8);
        Files.writeString(worktree.resolve("NewFile.java"), "final class NewFile {}\n", StandardCharsets.UTF_8);

        String status = executor.status(worktree);
        assertTrue(status.contains("README.md"));
        assertTrue(status.contains("NewFile.java"));
        assertTrue(executor.diff(worktree, baselineCommit).contains("+changed"));
        assertTrue(executor.log(bareRepository, baselineCommit, 5).contains("initial fixture"));
        assertEquals("baseline\n", executor.show(
                bareRepository, baselineCommit, new DiffPath("README.md")));

        executor.stageAll(worktree);
        GitTreeId tree = executor.writeTree(worktree);
        RepositoryCommitId deliveryCommit = executor.commitTree(
                bareRepository,
                tree,
                baselineCommit,
                new GitCommitMessage("CrewScope delivery"));
        assertEquals(tree, executor.commitTreeId(bareRepository, deliveryCommit));
        assertTrue(executor.hasSingleParent(bareRepository, deliveryCommit, baselineCommit));
        assertEquals("changed\n", executor.show(
                bareRepository, deliveryCommit, new DiffPath("README.md")));

        ExecutionWorkspaceKey workspaceKey = ExecutionWorkspaceKey.derive(
                ExecutionWorkspaceId.generate(), 1);
        WorkspaceArchiveReference archiveReference =
                WorkspaceArchiveReference.derive(workspaceKey);
        executor.createArchiveReference(bareRepository, archiveReference, deliveryCommit);
        assertEquals(
                deliveryCommit,
                executor.findArchiveReference(bareRepository, archiveReference).orElseThrow());
        assertEquals(
                deliveryCommit.value(),
                runRequired(
                                temporaryDirectory,
                                "git",
                                "-C",
                                bareRepository.toString(),
                                "rev-parse",
                                archiveReference.value())
                        .trim());

        executor.removeWorktree(bareRepository, worktree);
        executor.pruneWorktrees(bareRepository);
        executor.deleteManagedBranch(bareRepository, branch, baselineCommit);
        assertFalse(Files.exists(worktree));
        assertTrue(executor.findManagedBranch(bareRepository, branch).isEmpty());
    }

    @Test
    void restrictsM4I05StatusDiffAndHistoryToAllowedPathsWithoutBinaryPatch() throws Exception {
        ManagedWorkspaceBranch branch = ManagedWorkspaceBranch.derive(TaskExecutionId.generate(), 1);
        Path worktree = temporaryDirectory.resolve("inspection-worktree");
        executor.addWorktree(bareRepository, worktree, branch, baselineCommit);
        Files.writeString(worktree.resolve("README.md"), "allowed change\n", StandardCharsets.UTF_8);
        Files.writeString(worktree.resolve("secret.txt"), "outside policy\n", StandardCharsets.UTF_8);
        Files.writeString(worktree.resolve(".env"), "TOKEN=must-not-leak\n", StandardCharsets.UTF_8);
        runRequired(worktree, "git", "add", ".env");

        AllowedPathSet allowed = AllowedPathSet.of("README.md");
        String status = executor.inspectionStatus(worktree, allowed);
        assertTrue(status.contains("README.md"));
        assertFalse(status.contains("secret.txt"));

        String diff = executor.inspectionDiff(worktree, baselineCommit, allowed);
        assertTrue(diff.contains("+allowed change"));
        assertFalse(diff.contains("secret.txt"));
        assertFalse(diff.contains("GIT binary patch"));

        String fullStatus = executor.inspectionStatus(worktree, AllowedPathSet.of("."));
        assertTrue(fullStatus.contains("secret.txt"));
        assertFalse(fullStatus.contains(".env"));
        String fullDiff = executor.inspectionDiff(
                worktree, baselineCommit, AllowedPathSet.of("."));
        assertTrue(fullDiff.contains("README.md"));
        assertFalse(fullDiff.contains("must-not-leak"));

        String firstPage = executor.inspectionLog(
                bareRepository, baselineCommit, 0, 1, allowed);
        assertTrue(firstPage.contains("initial fixture"));
        assertEquals(
                "",
                executor.inspectionLog(bareRepository, baselineCommit, 1, 1, allowed));
    }

    @Test
    void treatsM4I05AllowedRootsAsLiteralGitPathsInsteadOfPathspecMagic() throws Exception {
        ManagedWorkspaceBranch branch = ManagedWorkspaceBranch.derive(TaskExecutionId.generate(), 1);
        Path worktree = temporaryDirectory.resolve("literal-pathspec-worktree");
        executor.addWorktree(bareRepository, worktree, branch, baselineCommit);
        Files.writeString(worktree.resolve(":!README.md"), "literal path\n", StandardCharsets.UTF_8);
        Files.writeString(worktree.resolve("outside.txt"), "outside policy\n", StandardCharsets.UTF_8);
        runRequired(
                worktree,
                "git",
                "--literal-pathspecs",
                "add",
                "--",
                ":!README.md",
                "outside.txt");

        AllowedPathSet allowed = AllowedPathSet.of(":!README.md");
        String status = executor.inspectionStatus(worktree, allowed);
        assertTrue(status.contains(":!README.md"));
        assertFalse(status.contains("outside.txt"));

        String diff = executor.inspectionDiff(worktree, baselineCommit, allowed);
        assertTrue(diff.contains("+literal path"));
        assertFalse(diff.contains("outside policy"));
    }

    @Test
    void mapsRepositoryReferenceAndConflictFailuresToStableSafeErrors() {
        Path nonRepository = temporaryDirectory.resolve("not-a-repository");
        assertTrue(nonRepository.toFile().mkdir());

        GitCommandException repositoryFailure =
                assertThrows(GitCommandException.class, () -> executor.status(nonRepository));
        assertEquals(GitCommandError.NOT_A_REPOSITORY, repositoryFailure.error());
        assertFalse(repositoryFailure.getMessage().contains(nonRepository.toString()));

        GitCommandException referenceFailure = assertThrows(
                GitCommandException.class,
                () -> executor.resolveBranch(
                        bareRepository, new RepositoryBranchName("missing-branch")));
        assertEquals(GitCommandError.INVALID_REFERENCE, referenceFailure.error());
        assertFalse(referenceFailure.getMessage().contains("missing-branch"));

        ManagedWorkspaceBranch branch = ManagedWorkspaceBranch.derive(TaskExecutionId.generate(), 1);
        Path worktree = temporaryDirectory.resolve("conflicting-worktree");
        executor.addWorktree(bareRepository, worktree, branch, baselineCommit);
        GitCommandException conflict = assertThrows(
                GitCommandException.class,
                () -> executor.addWorktree(
                        bareRepository,
                        temporaryDirectory.resolve("second-worktree"),
                        branch,
                        baselineCommit));
        assertEquals(GitCommandError.CONFLICT, conflict.error());
    }

    @Test
    void argumentArraysKeepMetacharactersLiteralAndValueObjectsRejectOptionInjection()
            throws Exception {
        Path marker = temporaryDirectory.resolve("injected-marker");
        Path hookMarker = temporaryDirectory.resolve("hook-marker");
        Path literalWorktree = temporaryDirectory.resolve("workspace;touch injected-marker");
        ManagedWorkspaceBranch branch = ManagedWorkspaceBranch.derive(TaskExecutionId.generate(), 1);
        writeExecutable(
                bareRepository.resolve("hooks/post-checkout"),
                "touch '" + hookMarker + "'\n");

        executor.addWorktree(bareRepository, literalWorktree, branch, baselineCommit);
        assertTrue(Files.isDirectory(literalWorktree));
        assertFalse(Files.exists(marker));
        assertFalse(Files.exists(hookMarker));

        assertThrows(DomainValidationException.class, () -> new RepositoryBranchName("--help"));
        assertThrows(DomainValidationException.class, () -> new RepositoryCommitId("HEAD"));
        assertThrows(DomainValidationException.class, () -> new DiffPath("../outside"));

        GitCommandException missingPath = assertThrows(
                GitCommandException.class,
                () -> executor.show(
                        bareRepository,
                        baselineCommit,
                        new DiffPath("README.md;touch injected-marker")));
        assertEquals(GitCommandError.INVALID_REFERENCE, missingPath.error());
        assertFalse(Files.exists(marker));
    }

    @Test
    void fixesEnvironmentAndDoesNotInheritUnrelatedVariables() throws Exception {
        Path script = executableScript(
                "environment-git.sh",
                "printf '%s|%s|%s|%s|%s|%s' \"$HOME\" \"$GIT_CONFIG_NOSYSTEM\" "
                        + "\"$GIT_CONFIG_GLOBAL\" \"$GIT_TERMINAL_PROMPT\" \"$LC_ALL\" "
                        + "\"${JAVA_HOME-unset}\"\n");
        Path commandHome = temporaryDirectory.resolve("isolated-home");
        GitCommandExecutor scripted = new GitCommandExecutor(
                // This case verifies environment isolation, so leave timing enforcement to the
                // dedicated 100 ms timeout case and tolerate full-suite host load here.
                new GitCommandPolicy(commandHome, Duration.ofSeconds(5), 4096),
                script.toString());

        assertEquals(
                commandHome.toAbsolutePath().normalize() + "|1|/dev/null|0|C|unset",
                scripted.status(temporaryDirectory));
    }

    @Test
    void terminatesTimedOutProcessWithStableClassification() throws Exception {
        Path script = executableScript("slow-git.sh", "sleep 5\n");
        GitCommandExecutor scripted = new GitCommandExecutor(
                new GitCommandPolicy(
                        temporaryDirectory.resolve("slow-home"), Duration.ofMillis(100), 4096),
                script.toString());

        long started = System.nanoTime();
        GitCommandException failure = assertThrows(
                GitCommandException.class, () -> scripted.status(temporaryDirectory));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(GitCommandError.TIMEOUT, failure.error());
        assertTrue(elapsedMillis < 3_000, "timed-out process should be terminated promptly");
    }

    @Test
    void terminatesOutputFloodAtTheConfiguredBound() throws Exception {
        Path script = executableScript(
                "noisy-git.sh", "while :; do printf '0123456789abcdef'; done\n");
        GitCommandExecutor scripted = new GitCommandExecutor(
                new GitCommandPolicy(
                        temporaryDirectory.resolve("noisy-home"), Duration.ofSeconds(5), 1024),
                script.toString());

        GitCommandException failure = assertThrows(
                GitCommandException.class, () -> scripted.status(temporaryDirectory));

        assertEquals(GitCommandError.OUTPUT_LIMIT, failure.error());
        assertFalse(failure.getMessage().contains("0123456789abcdef"));
    }

    @Test
    void policyAndTypedCommitInputsFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GitCommandPolicy(temporaryDirectory, Duration.ZERO, 4096));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GitCommandPolicy(
                        temporaryDirectory, Duration.ofMinutes(6), 4096));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GitCommandPolicy(
                        temporaryDirectory, Duration.ofSeconds(1), 100));
        assertThrows(IllegalArgumentException.class, () -> new GitTreeId("HEAD"));
        assertThrows(IllegalArgumentException.class, () -> new GitCommitMessage(" \n"));
    }

    private Path executableScript(String name, String body) throws IOException {
        Path script = temporaryDirectory.resolve(name);
        writeExecutable(script, body);
        return script;
    }

    private static void writeExecutable(Path script, String body) throws IOException {
        Files.writeString(script, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
        assertTrue(script.toFile().setExecutable(true));
    }

    private static boolean commandSucceeds(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(HOST_COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    && process.exitValue() == 0;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static String runRequired(Path workingDirectory, String... command)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(List.of(command))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(HOST_COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("Fixture command timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Fixture command failed: " + output);
        }
        return output;
    }
}
