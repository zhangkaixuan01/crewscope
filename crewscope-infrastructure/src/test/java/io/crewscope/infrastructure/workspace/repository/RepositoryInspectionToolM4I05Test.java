package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.ManagedWorkspaceBranch;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fast security, pagination and AgentScope metadata tests for M4-I05. */
class RepositoryInspectionToolM4I05Test {

    private static final String CONTAINER_ROOT = "/workspace/repository";

    @TempDir Path worktreePath;

    private TaskExecutionSandboxCall call;
    private AbstractFilesystem filesystem;
    private GitCommandExecutor gitCommands;
    private ManagedWorktree worktree;
    private RepositoryInspectionTool tool;
    private RuntimeContext runtimeContext;

    @BeforeEach
    void setUp() throws Exception {
        call = mock(TaskExecutionSandboxCall.class);
        filesystem = mock(AbstractFilesystem.class);
        gitCommands = mock(GitCommandExecutor.class);
        Files.createDirectories(worktreePath.resolve("src"));
        Files.writeString(worktreePath.resolve("src/Main.java"), "class Main {}\n");
        Files.writeString(worktreePath.resolve("src/Other.java"), "class Other {}\n");
        Files.writeString(worktreePath.resolve("src/.env"), "TOKEN=secret\n");
        Files.createSymbolicLink(worktreePath.resolve("src/link"), worktreePath.resolve("src/Main.java"));

        RepositoryKey repositoryKey = new RepositoryKey("crewscope");
        ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        TaskExecutionId executionId = TaskExecutionId.generate();
        RepositoryCommitId baseline = new RepositoryCommitId("a".repeat(40));
        worktree = new ManagedWorktree(
                workspaceId,
                repositoryKey,
                ExecutionWorkspaceKey.derive(workspaceId, 1),
                ManagedWorkspaceBranch.derive(executionId, 1),
                baseline,
                baseline,
                new WorkspacePhysicalFingerprint(TaskFactHash.sha256("physical").value()),
                worktreePath.toRealPath());
        ManagedRepository repository = new ManagedRepository(
                repositoryKey, worktreePath.resolve("managed.git").toAbsolutePath());
        AllowedPathSet allowedPaths = AllowedPathSet.of("src");
        tool = new RepositoryInspectionTool(
                call,
                filesystem,
                gitCommands,
                worktree,
                repository,
                new RepositoryInspectionPathGuard(
                        worktreePath.toRealPath(), CONTAINER_ROOT, allowedPaths, 64),
                allowedPaths,
                20,
                20,
                4,
                8,
                1024);
        runtimeContext = RuntimeContext.builder()
                .userId("m4-i05-user")
                .sessionId("m4-i05-session")
                .build();
    }

    @Test
    void registersExactlyEightPlanModeSafeReadOnlyTools() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);

        Set<String> expected = Set.of(
                "repository_tree",
                "repository_list",
                "repository_read",
                "repository_grep",
                "repository_glob",
                "repository_git_history",
                "repository_git_status",
                "repository_git_diff");
        assertEquals(expected, toolkit.getToolNames());
        expected.forEach(name -> assertTrue(toolkit.getTool(name).isReadOnly()));
        assertFalse(toolkit.getToolNames().contains("write_file"));
        assertFalse(toolkit.getToolNames().contains("edit_file"));
        assertFalse(toolkit.getToolNames().contains("execute"));
    }

    @Test
    void delegatesListGlobAndGrepWhileFilteringSensitiveAndSymbolicLinkResults() {
        when(filesystem.ls(runtimeContext, CONTAINER_ROOT + "/src"))
                .thenReturn(LsResult.success(List.of(
                        FileInfo.ofFile(CONTAINER_ROOT + "/src/Other.java", 15, ""),
                        FileInfo.ofFile(CONTAINER_ROOT + "/src/.env", 12, ""),
                        FileInfo.ofFile(CONTAINER_ROOT + "/src/link", 10, ""),
                        FileInfo.ofFile(CONTAINER_ROOT + "/src/Main.java", 14, ""))));
        String list = tool.list(runtimeContext, "src", 1, 1);
        assertTrue(list.contains("returned=1 offset=1"));
        assertTrue(list.contains("src/Other.java"));
        assertFalse(list.contains(".env"));
        assertFalse(list.contains("src/link"));

        when(filesystem.glob(runtimeContext, "**/*.java", CONTAINER_ROOT + "/src"))
                .thenReturn(GlobResult.success(List.of(
                        FileInfo.ofFile(CONTAINER_ROOT + "/src/Main.java", 14, ""))));
        assertTrue(tool.glob(runtimeContext, "**/*.java", "src", 0, 10)
                .contains("src/Main.java"));

        when(filesystem.grep(runtimeContext, "Main", CONTAINER_ROOT + "/src", "*.java"))
                .thenReturn(GrepResult.success(List.of(
                        new GrepMatch(CONTAINER_ROOT + "/src/Main.java", 1, "class Main {}"))));
        assertTrue(tool.grep(runtimeContext, "Main", "src", "*.java", 0, 10)
                .contains("src/Main.java:1:class Main {}"));
        verify(call, times(3)).requireCurrent();
    }

    @Test
    void readsLinePagesAndRejectsBinarySensitiveTraversalAndOutOfPolicyPaths() {
        when(filesystem.read(runtimeContext, CONTAINER_ROOT + "/src/Main.java", 1, 3))
                .thenReturn(ReadResult.success(new FileData("two\nthree\nfour", "utf-8")));
        String page = tool.read(runtimeContext, "src/Main.java", 1, 2);
        assertTrue(page.contains("returned=2 offset=1"));
        assertTrue(page.contains("2:two"));
        assertTrue(page.contains("hasMore=true"));

        when(filesystem.read(runtimeContext, CONTAINER_ROOT + "/src/image.png", 0, 2))
                .thenReturn(ReadResult.success(new FileData("AA==", "base64")));
        assertEquals(
                RepositoryInspectionError.BINARY_FILE,
                assertThrows(
                                RepositoryInspectionException.class,
                                () -> tool.read(runtimeContext, "src/image.png", 0, 1))
                        .error());
        assertEquals(
                RepositoryInspectionError.SENSITIVE_PATH,
                assertThrows(
                                RepositoryInspectionException.class,
                                () -> tool.read(runtimeContext, "src/.env", 0, 1))
                        .error());
        assertEquals(
                RepositoryInspectionError.INVALID_PATH,
                assertThrows(
                                RepositoryInspectionException.class,
                                () -> tool.read(runtimeContext, "../outside", 0, 1))
                        .error());
        assertEquals(
                RepositoryInspectionError.PATH_NOT_ALLOWED,
                assertThrows(
                                RepositoryInspectionException.class,
                                () -> tool.read(runtimeContext, "README.md", 0, 1))
                        .error());
    }

    @Test
    void exposesPagedTypedGitReadsAndNeverReturnsBinaryPatchPayload() {
        when(gitCommands.inspectionStatus(worktree.canonicalPath(), AllowedPathSet.of("src")))
                .thenReturn(" M src/Main.java\0?? src/Other.java\0");
        assertTrue(tool.gitStatus(runtimeContext, 0, 1).contains("hasMore=true"));

        when(gitCommands.inspectionDiff(
                        worktree.canonicalPath(),
                        worktree.baselineCommit(),
                        AllowedPathSet.of("src")))
                .thenReturn("diff --git a/src/Main.java b/src/Main.java\n+class Main {}");
        String diff = tool.gitDiff(runtimeContext, 1, 1);
        assertTrue(diff.contains("+class Main {}"));
        assertFalse(diff.contains("GIT binary patch"));

        when(gitCommands.inspectionLog(
                        worktreePath.resolve("managed.git").toAbsolutePath(),
                        worktree.headCommit(),
                        0,
                        2,
                        AllowedPathSet.of("src")))
                .thenReturn("a".repeat(40) + "\0\0" + "1\0initial\0");
        assertTrue(tool.gitHistory(runtimeContext, 0, 1).contains("subject=initial"));
    }

    @Test
    void rejectsCallsAfterGuardedContextBecomesInvalid() {
        org.mockito.Mockito.doThrow(new IllegalStateException("closed"))
                .when(call)
                .requireCurrent();

        RepositoryInspectionException failure = assertThrows(
                RepositoryInspectionException.class,
                () -> tool.list(runtimeContext, "src", 0, 1));

        assertEquals(RepositoryInspectionError.INVALID_CONTEXT, failure.error());
        assertFalse(failure.getMessage().contains(worktreePath.toString()));
    }

    @Test
    void rejectsHiddenBinaryTextAndKeepsRenderedResultsWithinByteBudget() {
        when(filesystem.read(runtimeContext, CONTAINER_ROOT + "/src/Main.java", 0, 2))
                .thenReturn(ReadResult.success(new FileData("text\u0000binary", "utf-8")));
        RepositoryInspectionException hiddenBinary = assertThrows(
                RepositoryInspectionException.class,
                () -> tool.read(runtimeContext, "src/Main.java", 0, 1));
        assertEquals(RepositoryInspectionError.BINARY_FILE, hiddenBinary.error());

        String oversized = "你".repeat(1000);
        when(filesystem.read(runtimeContext, CONTAINER_ROOT + "/src/Other.java", 0, 2))
                .thenReturn(ReadResult.success(new FileData(oversized, "utf-8")));
        String bounded = tool.read(runtimeContext, "src/Other.java", 0, 1);
        assertTrue(bounded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024);
    }

    @Test
    void failsTreeTraversalInsteadOfReturningANonAdvancingPageAtTheOperationLimit() {
        RepositoryInspectionTool limitedTreeTool = new RepositoryInspectionTool(
                call,
                filesystem,
                gitCommands,
                worktree,
                new ManagedRepository(
                        worktree.repositoryKey(),
                        worktreePath.resolve("managed.git").toAbsolutePath()),
                new RepositoryInspectionPathGuard(
                        worktreePath.toAbsolutePath(),
                        CONTAINER_ROOT,
                        AllowedPathSet.of("src"),
                        64),
                AllowedPathSet.of("src"),
                20,
                20,
                4,
                1,
                1024);
        when(filesystem.ls(runtimeContext, CONTAINER_ROOT + "/src"))
                .thenReturn(LsResult.success(List.of(
                        FileInfo.ofDir(CONTAINER_ROOT + "/src/nested", ""))));

        RepositoryInspectionException failure = assertThrows(
                RepositoryInspectionException.class,
                () -> limitedTreeTool.tree(runtimeContext, "src", 2, 1, 1));

        assertEquals(RepositoryInspectionError.TRAVERSAL_LIMIT, failure.error());
        assertFalse(failure.getMessage().contains(worktreePath.toString()));
    }
}
