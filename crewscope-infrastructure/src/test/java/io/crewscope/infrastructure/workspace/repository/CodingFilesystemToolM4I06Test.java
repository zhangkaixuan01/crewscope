package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fast mutation, budget and fixed attack-set coverage for M4-I06. */
class CodingFilesystemToolM4I06Test {

    private static final String CONTAINER_ROOT = "/workspace/repository";

    @TempDir Path root;

    private TaskExecutionSandboxCall call;
    private AbstractFilesystem filesystem;
    private RuntimeContext runtimeContext;

    @BeforeEach
    void setUp() throws Exception {
        call = mock(TaskExecutionSandboxCall.class);
        filesystem = mock(AbstractFilesystem.class);
        runtimeContext = RuntimeContext.builder()
                .userId("m4-i06-user")
                .sessionId("m4-i06-session")
                .build();
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"), "one\ntwo\nthree\n");
        installHostBackedAgentScopeFilesystem();
    }

    @Test
    void registersExactlyFiveExplicitNonReadOnlyMutationTools() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool(largeBudget(), CodingFilesystemMutationHook.NONE));

        Set<String> expected = Set.of(
                "coding_create", "coding_edit", "coding_patch", "coding_move", "coding_delete");
        assertEquals(expected, toolkit.getToolNames());
        expected.forEach(name -> assertFalse(toolkit.getTool(name).isReadOnly()));
        assertFalse(toolkit.getToolNames().contains("write_file"));
        assertFalse(toolkit.getToolNames().contains("execute"));
    }

    @Test
    void createsEditsPatchesMovesAndDeletesThroughAgentScopeFilesystem() throws Exception {
        CodingFilesystemTool tool = tool(largeBudget(), CodingFilesystemMutationHook.NONE);

        assertTrue(tool.create(runtimeContext, "src/New.java", "class New {}\n")
                .contains("status=created"));
        assertTrue(tool.edit(runtimeContext, "src/Main.java", "two", "TWO", false)
                .contains("status=edited"));
        String patch = "@@ -1,3 +1,4 @@\n one\n-TWO\n+second\n three\n+four\n";
        assertTrue(tool.patch(runtimeContext, "src/Main.java", patch)
                .contains("status=patched"));
        assertTrue(tool.move(runtimeContext, "src/New.java", "src/Renamed.java")
                .contains("status=moved"));
        assertTrue(tool.delete(runtimeContext, "src/Renamed.java")
                .contains("status=deleted"));

        assertEquals("one\nsecond\nthree\nfour\n", Files.readString(root.resolve("src/Main.java")));
        assertFalse(Files.exists(root.resolve("src/New.java")));
        assertFalse(Files.exists(root.resolve("src/Renamed.java")));
        verify(call, org.mockito.Mockito.times(5)).requireCurrent();
    }

    @Test
    void rejectsTraversalScopeSensitiveSymlinkAndCaseAttacksBeforeAnyWrite() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(root.resolve("src/link"), outside);
        CodingFilesystemTool tool = tool(largeBudget(), CodingFilesystemMutationHook.NONE);
        List<Attack> attacks = List.of(
                new Attack("../escape.java", CodingFilesystemError.INVALID_PATH),
                new Attack("/tmp/escape.java", CodingFilesystemError.INVALID_PATH),
                new Attack("src\\escape.java", CodingFilesystemError.INVALID_PATH),
                new Attack("README.md", CodingFilesystemError.PATH_NOT_ALLOWED),
                new Attack("src/.env", CodingFilesystemError.SENSITIVE_PATH),
                new Attack("src/link/escape.java", CodingFilesystemError.SYMBOLIC_LINK),
                new Attack("src/main.java", CodingFilesystemError.CASE_COLLISION));

        for (Attack attack : attacks) {
            CodingFilesystemException failure = assertThrows(
                    CodingFilesystemException.class,
                    () -> tool.create(runtimeContext, attack.path(), "blocked\n"));
            assertEquals(attack.error(), failure.error());
            assertFalse(failure.getMessage().contains(root.toString()));
            assertNull(failure.getCause());
        }

        verify(filesystem, never()).uploadFiles(any(), any());
        assertFalse(Files.exists(outside.resolve("escape.java")));
    }

    @Test
    void enforcesSingleFileCumulativeOperationAndChangedFileBudgetsBeforeMutation() {
        WorkspaceOperationBudget budget =
                new WorkspaceOperationBudget(10, 2, 10, 2, 15, 100, 1);
        CodingFilesystemTool tool = tool(budget, CodingFilesystemMutationHook.NONE);
        tool.create(runtimeContext, "src/A.java", "12345678");

        assertBudgetFailure(() -> tool.create(runtimeContext, "src/B.java", "12345678"));
        assertFalse(Files.exists(root.resolve("src/B.java")));
        tool.create(runtimeContext, "src/C.java", "1");
        assertBudgetFailure(() -> tool.create(runtimeContext, "src/D.java", "1"));
        assertFalse(Files.exists(root.resolve("src/D.java")));

        CodingFilesystemTool sizeLimited = tool(
                new WorkspaceOperationBudget(10, 5, 5, 5, 20, 100, 1),
                CodingFilesystemMutationHook.NONE);
        assertBudgetFailure(() -> sizeLimited.create(runtimeContext, "src/Large.java", "123456"));
        assertBudgetFailure(() -> sizeLimited.edit(
                runtimeContext, "src/Main.java", "123456", "x", false));
        assertFalse(Files.exists(root.resolve("src/Large.java")));
    }

    @Test
    void rejectsStaleOrMalformedPatchWithoutChangingTheFile() throws Exception {
        CodingFilesystemTool tool = tool(largeBudget(), CodingFilesystemMutationHook.NONE);
        String before = Files.readString(root.resolve("src/Main.java"));

        CodingFilesystemException stale = assertThrows(
                CodingFilesystemException.class,
                () -> tool.patch(
                        runtimeContext,
                        "src/Main.java",
                        "@@ -1,1 +1,1 @@\n-missing\n+replacement\n"));
        assertEquals(CodingFilesystemError.STALE_CONTENT, stale.error());
        CodingFilesystemException invalid = assertThrows(
                CodingFilesystemException.class,
                () -> tool.patch(runtimeContext, "src/Main.java", "--- a/src/Main.java\n"));
        assertEquals(CodingFilesystemError.PATCH_INVALID, invalid.error());
        assertEquals(before, Files.readString(root.resolve("src/Main.java")));
        verify(filesystem, never()).write(any(), anyString(), anyString());
    }

    @Test
    void detectsParentSwapInFinalToctouWindowAndPerformsZeroOutsideWrites() throws Exception {
        Path safe = Files.createDirectory(root.resolve("src/safe"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        CodingFilesystemMutationHook swap = (operation, paths) -> {
            if ("create".equals(operation)) {
                try {
                    Files.delete(safe);
                    Files.createSymbolicLink(safe, outside);
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
        CodingFilesystemTool tool = tool(largeBudget(), swap);

        CodingFilesystemException failure = assertThrows(
                CodingFilesystemException.class,
                () -> tool.create(runtimeContext, "src/safe/Escape.java", "blocked\n"));

        assertEquals(CodingFilesystemError.TOCTOU_DETECTED, failure.error());
        verify(filesystem, never()).write(any(), anyString(), anyString());
        assertFalse(Files.exists(outside.resolve("Escape.java")));
    }

    @Test
    void rejectsDirectoriesAndInvalidContextWithoutInvokingMutationBackend() throws Exception {
        Files.createDirectory(root.resolve("src/directory"));
        CodingFilesystemTool tool = tool(largeBudget(), CodingFilesystemMutationHook.NONE);
        assertEquals(
                CodingFilesystemError.NOT_REGULAR_FILE,
                assertThrows(
                                CodingFilesystemException.class,
                                () -> tool.delete(runtimeContext, "src/directory"))
                        .error());

        org.mockito.Mockito.doThrow(new IllegalStateException("closed"))
                .when(call)
                .requireCurrent();
        assertEquals(
                CodingFilesystemError.INVALID_CONTEXT,
                assertThrows(
                                CodingFilesystemException.class,
                                () -> tool.create(runtimeContext, "src/Blocked.java", "blocked"))
                        .error());
        verify(filesystem, never()).delete(any(), anyString());
    }

    private CodingFilesystemTool tool(
            WorkspaceOperationBudget budget, CodingFilesystemMutationHook hook) {
        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        when(workspace.id()).thenReturn(workspaceId);
        when(workspace.workspaceKey()).thenReturn(ExecutionWorkspaceKey.derive(workspaceId, 1));
        when(workspace.taskExecutionId()).thenReturn(TaskExecutionId.generate());
        when(workspace.attempt()).thenReturn(1);
        WorkspacePolicy policy = mock(WorkspacePolicy.class);
        when(policy.policyHash()).thenReturn(TaskFactHash.sha256("m4-i06-policy"));
        when(policy.operationBudget()).thenReturn(budget);
        CodingFilesystemUsage usage = new CodingFilesystemUsage(
                workspace, policy, Set.of(), 0);
        return new CodingFilesystemTool(
                call,
                filesystem,
                new CodingFilesystemPathPolicy(
                        root.toAbsolutePath(), CONTAINER_ROOT, AllowedPathSet.of("src")),
                usage,
                budget,
                1024,
                20,
                hook);
    }

    private void installHostBackedAgentScopeFilesystem() {
        when(filesystem.write(any(), anyString(), anyString())).thenAnswer(invocation -> {
            Path target = host(invocation.getArgument(1));
            Files.createDirectories(target.getParent());
            Files.writeString(target, invocation.getArgument(2), StandardCharsets.UTF_8);
            return WriteResult.ok(invocation.getArgument(1));
        });
        when(filesystem.uploadFiles(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<java.util.Map.Entry<String, byte[]>> files = invocation.getArgument(1);
            for (java.util.Map.Entry<String, byte[]> file : files) {
                Path target = host(file.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, file.getValue());
            }
            return files.stream()
                    .map(file -> FileUploadResponse.success(file.getKey()))
                    .toList();
        });
        when(filesystem.move(any(), anyString(), anyString())).thenAnswer(invocation -> {
            Path source = host(invocation.getArgument(1));
            Path destination = host(invocation.getArgument(2));
            Files.createDirectories(destination.getParent());
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            return WriteResult.ok(invocation.getArgument(2));
        });
        when(filesystem.delete(any(), anyString())).thenAnswer(invocation -> {
            Files.delete(host(invocation.getArgument(1)));
            return WriteResult.ok(invocation.getArgument(1));
        });
    }

    private Path host(String containerPath) {
        assertTrue(containerPath.startsWith(CONTAINER_ROOT + "/"));
        return root.resolve(containerPath.substring(CONTAINER_ROOT.length() + 1));
    }

    private static WorkspaceOperationBudget largeBudget() {
        return new WorkspaceOperationBudget(10, 20, 1024, 20, 4096, 4096, 2);
    }

    private static void assertBudgetFailure(org.junit.jupiter.api.function.Executable executable) {
        assertEquals(
                CodingFilesystemError.BUDGET_EXCEEDED,
                assertThrows(CodingFilesystemException.class, executable).error());
    }

    private record Attack(String path, CodingFilesystemError error) {}
}
