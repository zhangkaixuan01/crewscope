package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.RepositoryKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemCodingEvaluationJudgeHookM4Q03Test {

    @TempDir Path temporaryDirectory;

    @Test
    void injectsJudgesIdempotentlyBeforeMonitoringAndKeepsThemOutOfGit() throws Exception {
        Fixture fixture = fixture();
        Path judges = Files.createDirectory(temporaryDirectory.resolve("judges"));
        writeJudge(judges, "task-a", "AlphaJudgeTest.java", "class AlphaJudgeTest {}\n");
        writeJudge(judges, "task-b", "BetaJudgeTest.java", "class BetaJudgeTest {}\n");
        var hook = new FilesystemCodingEvaluationJudgeHook(judges, "q03-default");
        when(fixture.target().allowedPaths())
                .thenReturn(new io.crewscope.domain.coding.CodingTargetAllowedPaths(
                        java.util.List.of("src/main/java/io/crewscope/evaluation/Alpha.java")));

        hook.prepare(
                fixture.workspace(), fixture.target(), fixture.repository(), fixture.worktree());
        hook.prepare(
                fixture.workspace(), fixture.target(), fixture.repository(), fixture.worktree());

        Path packagePath = fixture.worktreePath()
                .resolve("src/test/java/io/crewscope/evaluation");
        assertEquals(
                "class AlphaJudgeTest {}\n",
                Files.readString(packagePath.resolve("AlphaJudgeTest.java")));
        assertTrue(Files.notExists(packagePath.resolve("BetaJudgeTest.java")));
        assertEquals(
                FilesystemCodingEvaluationJudgeHook.EXCLUDE_PATTERN,
                run(fixture.worktreePath(), "git", "check-ignore", "--no-index", "-v",
                                "src/test/java/io/crewscope/evaluation/AlphaJudgeTest.java")
                        .lines()
                        .findFirst()
                        .orElseThrow()
                        .split(":", 3)[2]
                        .split("\\t", 2)[0]);
        assertTrue(run(fixture.worktreePath(), "git", "status", "--porcelain").isBlank());
    }

    @Test
    void skipsWorktreesOutsideTheConfiguredEvaluationRepository() throws Exception {
        Fixture fixture = fixture("non-evaluation");
        Path judges = Files.createDirectory(temporaryDirectory.resolve("isolated-judges"));
        writeJudge(judges, "task", "IsolatedJudgeTest.java", "class IsolatedJudgeTest {}\n");
        var hook = new FilesystemCodingEvaluationJudgeHook(judges, "coding-evaluation");
        when(fixture.target().allowedPaths())
                .thenReturn(new io.crewscope.domain.coding.CodingTargetAllowedPaths(
                        java.util.List.of("src/main/java/io/crewscope/evaluation/Isolated.java")));

        hook.prepare(
                fixture.workspace(), fixture.target(), fixture.repository(), fixture.worktree());

        assertTrue(Files.notExists(fixture.worktreePath()
                .resolve("src/test/java/io/crewscope/evaluation/IsolatedJudgeTest.java")));
        assertTrue(run(fixture.worktreePath(), "git", "status", "--porcelain").isBlank());
    }

    @Test
    void failsClosedForSymlinkJudgeEntriesAndConflictingDestinations() throws Exception {
        Fixture symlinkFixture = fixture("symlink");
        Path judges = Files.createDirectory(temporaryDirectory.resolve("symlink-judges"));
        Path external = temporaryDirectory.resolve("ExternalJudgeTest.java");
        Files.writeString(external, "class ExternalJudgeTest {}\n");
        Path task = Files.createDirectory(judges.resolve("task"));
        Files.createSymbolicLink(task.resolve("ExternalJudgeTest.java"), external);
        var symlinkHook = new FilesystemCodingEvaluationJudgeHook(
                judges, "q03-symlink");
        when(symlinkFixture.target().allowedPaths())
                .thenReturn(new io.crewscope.domain.coding.CodingTargetAllowedPaths(
                        java.util.List.of("src/main/java/io/crewscope/evaluation/External.java")));
        assertThrows(
                IllegalStateException.class,
                () -> symlinkHook.prepare(
                        symlinkFixture.workspace(),
                        symlinkFixture.target(),
                        symlinkFixture.repository(),
                        symlinkFixture.worktree()));

        Fixture conflictFixture = fixture("conflict");
        Path validJudges = Files.createDirectory(temporaryDirectory.resolve("valid-judges"));
        writeJudge(validJudges, "task", "ConflictJudgeTest.java", "class Expected {}\n");
        Path destination = conflictFixture.worktreePath()
                .resolve("src/test/java/io/crewscope/evaluation");
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("ConflictJudgeTest.java"), "class Replaced {}\n");
        var conflictHook = new FilesystemCodingEvaluationJudgeHook(
                validJudges, "q03-conflict");
        when(conflictFixture.target().allowedPaths())
                .thenReturn(new io.crewscope.domain.coding.CodingTargetAllowedPaths(
                        java.util.List.of("src/main/java/io/crewscope/evaluation/Conflict.java")));
        assertThrows(
                IllegalStateException.class,
                () -> conflictHook.prepare(
                        conflictFixture.workspace(),
                        conflictFixture.target(),
                        conflictFixture.repository(),
                        conflictFixture.worktree()));
    }

    @Test
    void failsClosedWhenAllowedSourcesDoNotResolveExactlyOneJudge() throws Exception {
        Fixture fixture = fixture("missing");
        Path judges = Files.createDirectory(temporaryDirectory.resolve("missing-judges"));
        writeJudge(judges, "task", "AvailableJudgeTest.java", "class AvailableJudgeTest {}\n");
        when(fixture.target().allowedPaths())
                .thenReturn(new io.crewscope.domain.coding.CodingTargetAllowedPaths(
                        java.util.List.of("src/main/java/io/crewscope/evaluation/Missing.java")));
        var hook = new FilesystemCodingEvaluationJudgeHook(judges, "q03-missing");

        assertThrows(
                IllegalStateException.class,
                () -> hook.prepare(
                        fixture.workspace(),
                        fixture.target(),
                        fixture.repository(),
                        fixture.worktree()));
    }

    @Test
    void propertiesDisableProductionBehaviorAndRejectUnsafeRoots() throws Exception {
        CodingEvaluationJudgeProperties disabled = new CodingEvaluationJudgeProperties();
        assertTrue(disabled.judgeTestsRootPath().isEmpty());

        CodingEvaluationJudgeProperties relative = new CodingEvaluationJudgeProperties();
        relative.setJudgeTestsRoot("relative/judges");
        assertThrows(IllegalArgumentException.class, relative::judgeTestsRootPath);

        Path physical = Files.createDirectory(temporaryDirectory.resolve("physical-judges"));
        Path link = temporaryDirectory.resolve("judge-link");
        Files.createSymbolicLink(link, physical);
        CodingEvaluationJudgeProperties symlink = new CodingEvaluationJudgeProperties();
        symlink.setJudgeTestsRoot(link.toString());
        assertThrows(IllegalArgumentException.class, symlink::judgeTestsRootPath);

        CodingEvaluationJudgeProperties invalidKey = new CodingEvaluationJudgeProperties();
        invalidKey.setRepositoryKey("../fixture");
        assertThrows(IllegalArgumentException.class, invalidKey::requiredRepositoryKey);
    }

    private Fixture fixture() throws Exception {
        return fixture("default");
    }

    private Fixture fixture(String suffix) throws Exception {
        Path seed = temporaryDirectory.resolve("seed-" + suffix);
        Files.createDirectory(seed);
        run(seed, "git", "init", "--initial-branch=main");
        run(seed, "git", "config", "user.name", "CrewScope Test");
        run(seed, "git", "config", "user.email", "test@crewscope.local");
        Files.writeString(seed.resolve("README.md"), "fixture\n", StandardCharsets.UTF_8);
        run(seed, "git", "add", "README.md");
        run(seed, "git", "commit", "-m", "fixture");

        Path repositoryPath = temporaryDirectory.resolve("repository-" + suffix + ".git");
        run(temporaryDirectory, "git", "clone", "--bare", seed.toString(), repositoryPath.toString());
        Path worktreePath = temporaryDirectory.resolve("worktree-" + suffix);
        run(
                temporaryDirectory,
                "git",
                "--git-dir=" + repositoryPath,
                "worktree",
                "add",
                worktreePath.toString(),
                "main");

        RepositoryKey repositoryKey = new RepositoryKey("q03-" + suffix);
        ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        CodingTargetSnapshotReference reference = mock(CodingTargetSnapshotReference.class);
        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        when(workspace.id()).thenReturn(workspaceId);
        when(workspace.repositoryKey()).thenReturn(repositoryKey);
        when(workspace.codingTarget()).thenReturn(reference);
        CodingTargetSnapshot target = mock(CodingTargetSnapshot.class);
        when(target.reference()).thenReturn(reference);
        ManagedRepository repository = mock(ManagedRepository.class);
        when(repository.repositoryKey()).thenReturn(repositoryKey);
        when(repository.canonicalPath()).thenReturn(repositoryPath.toRealPath());
        ManagedWorktree worktree = mock(ManagedWorktree.class);
        when(worktree.workspaceId()).thenReturn(workspaceId);
        when(worktree.repositoryKey()).thenReturn(repositoryKey);
        when(worktree.canonicalPath()).thenReturn(worktreePath.toRealPath());
        return new Fixture(workspace, target, repository, worktree, worktreePath);
    }

    private static void writeJudge(
            Path root, String task, String filename, String content) throws Exception {
        Path directory = Files.createDirectory(root.resolve(task));
        Files.writeString(directory.resolve(filename), content, StandardCharsets.UTF_8);
    }

    private static String run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("Command failed with exit " + exit + ": " + output);
        }
        return output;
    }

    private record Fixture(
            ExecutionWorkspace workspace,
            CodingTargetSnapshot target,
            ManagedRepository repository,
            ManagedWorktree worktree,
            Path worktreePath) {}
}
