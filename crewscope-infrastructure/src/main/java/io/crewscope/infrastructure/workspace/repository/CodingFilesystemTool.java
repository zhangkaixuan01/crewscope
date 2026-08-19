package io.crewscope.infrastructure.workspace.repository;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Agent-callable UTF-8 file mutations guarded by Workspace identity, paths and budgets. */
public final class CodingFilesystemTool {

    private final TaskExecutionSandboxCall call;
    private final AbstractFilesystem filesystem;
    private final CodingFilesystemPathPolicy pathPolicy;
    private final CodingFilesystemUsage usage;
    private final long maximumSingleFileBytes;
    private final int maximumPatchHunks;
    private final CodingFilesystemMutationHook mutationHook;

    CodingFilesystemTool(
            TaskExecutionSandboxCall call,
            AbstractFilesystem filesystem,
            CodingFilesystemPathPolicy pathPolicy,
            CodingFilesystemUsage usage,
            WorkspaceOperationBudget budget,
            int maximumToolContentBytes,
            int maximumPatchHunks,
            CodingFilesystemMutationHook mutationHook) {
        this.call = Objects.requireNonNull(call, "call");
        this.filesystem = Objects.requireNonNull(filesystem, "filesystem");
        this.pathPolicy = Objects.requireNonNull(pathPolicy, "pathPolicy");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.maximumSingleFileBytes = Math.min(
                Objects.requireNonNull(budget, "budget").maxSingleFileBytes(),
                maximumToolContentBytes);
        this.maximumPatchHunks = maximumPatchHunks;
        this.mutationHook = Objects.requireNonNull(mutationHook, "mutationHook");
    }

    @Tool(
            name = "coding_create",
            description = "Create one new UTF-8 file at an allowed repository-relative path.")
    public String create(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "New canonical repository-relative file")
                    String path,
            @ToolParam(name = "content", description = "Complete UTF-8 file content")
                    String content) {
        requireCall(runtimeContext);
        CodingFilesystemPathPolicy.requireText(content);
        long bytes = requireResultSize(content);
        CodingFilesystemPathPolicy.MutationPath target = pathPolicy.requireMissingFile(path);
        CodingFilesystemUsage.UsageSnapshot reserved =
                usage.reserve(Set.of(target.relative()), bytes);
        beforeMutation("create", List.of(target.relative()));
        target.witness().verify();
        target = pathPolicy.requireMissingFile(target.relative());
        WriteResult result = filesystem.write(runtimeContext, target.container(), content);
        if (!result.isSuccess()) {
            throw filesystemFailure();
        }
        verifyContent(target.relative(), content);
        return success("created", target.relative(), bytes, reserved);
    }

    @Tool(
            name = "coding_edit",
            description = "Replace one exact UTF-8 text block in an existing allowed file.")
    public String edit(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "Existing canonical repository-relative file")
                    String path,
            @ToolParam(name = "old_text", description = "Exact non-empty text to replace")
                    String oldText,
            @ToolParam(name = "new_text", description = "Replacement UTF-8 text")
                    String newText,
            @ToolParam(name = "replace_all", description = "Replace every exact occurrence")
                    boolean replaceAll) {
        requireCall(runtimeContext);
        requireEditText(oldText, newText);
        CodingFilesystemPathPolicy.ExistingTextFile current =
                pathPolicy.requireTextFile(path, maximumSingleFileBytes);
        int occurrences = occurrences(current.content(), oldText);
        if (occurrences == 0 || (!replaceAll && occurrences != 1)) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.STALE_CONTENT,
                    "Exact edit text is missing or no longer unique");
        }
        String replacement = replaceAll
                ? current.content().replace(oldText, newText)
                : replaceOnce(current.content(), oldText, newText);
        long bytes = requireResultSize(replacement);
        CodingFilesystemUsage.UsageSnapshot reserved =
                usage.reserve(Set.of(current.path().relative()), bytes);
        beforeMutation("edit", List.of(current.path().relative()));
        requireUnchanged(current);
        overwrite(runtimeContext, current.path().container(), replacement);
        verifyContent(current.path().relative(), replacement);
        return success("edited", current.path().relative(), bytes, reserved);
    }

    @Tool(
            name = "coding_patch",
            description = "Apply bounded unified hunks to one explicit allowed UTF-8 file.")
    public String patch(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "Existing canonical repository-relative file")
                    String path,
            @ToolParam(
                            name = "patch",
                            description = "Unified hunk text beginning with @@; no file paths")
                    String patch) {
        requireCall(runtimeContext);
        CodingFilesystemPathPolicy.requireText(patch);
        CodingFilesystemPathPolicy.ExistingTextFile current =
                pathPolicy.requireTextFile(path, maximumSingleFileBytes);
        String replacement = UnifiedTextPatch.apply(
                current.content(),
                patch,
                (int) maximumSingleFileBytes,
                maximumPatchHunks);
        if (replacement.equals(current.content())) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.PATCH_INVALID,
                    "Unified patch must change the repository file");
        }
        long bytes = requireResultSize(replacement);
        CodingFilesystemUsage.UsageSnapshot reserved =
                usage.reserve(Set.of(current.path().relative()), bytes);
        beforeMutation("patch", List.of(current.path().relative()));
        requireUnchanged(current);
        overwrite(runtimeContext, current.path().container(), replacement);
        verifyContent(current.path().relative(), replacement);
        return success("patched", current.path().relative(), bytes, reserved);
    }

    @Tool(
            name = "coding_move",
            description = "Move one regular file between two allowed repository-relative paths.")
    public String move(
            RuntimeContext runtimeContext,
            @ToolParam(name = "source", description = "Existing canonical source file")
                    String source,
            @ToolParam(name = "destination", description = "New canonical destination file")
                    String destination) {
        requireCall(runtimeContext);
        CodingFilesystemPathPolicy.ExistingTextFile current =
                pathPolicy.requireTextFile(source, maximumSingleFileBytes);
        CodingFilesystemPathPolicy.MutationPath target =
                pathPolicy.requireMissingFile(destination);
        CodingFilesystemUsage.UsageSnapshot reserved = usage.reserve(
                Set.of(current.path().relative(), target.relative()), 0);
        beforeMutation("move", List.of(current.path().relative(), target.relative()));
        requireUnchanged(current);
        target.witness().verify();
        target = pathPolicy.requireMissingFile(target.relative());
        WriteResult result = filesystem.move(
                runtimeContext, current.path().container(), target.container());
        if (!result.isSuccess()) {
            throw filesystemFailure();
        }
        pathPolicy.verifyAbsent(current.path().relative());
        verifyContent(target.relative(), current.content());
        return success("moved", target.relative(), 0, reserved);
    }

    @Tool(
            name = "coding_delete",
            description = "Delete one existing regular file at an allowed repository-relative path.")
    public String delete(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "Existing canonical repository-relative file")
                    String path) {
        requireCall(runtimeContext);
        CodingFilesystemPathPolicy.MutationPath target =
                pathPolicy.requireExistingRegularFile(path);
        CodingFilesystemUsage.UsageSnapshot reserved =
                usage.reserve(Set.of(target.relative()), 0);
        beforeMutation("delete", List.of(target.relative()));
        target = pathPolicy.requireExistingRegularFile(target.relative());
        WriteResult result = filesystem.delete(runtimeContext, target.container());
        if (!result.isSuccess()) {
            throw filesystemFailure();
        }
        pathPolicy.verifyAbsent(target.relative());
        return success("deleted", target.relative(), 0, reserved);
    }

    private void requireCall(RuntimeContext runtimeContext) {
        Objects.requireNonNull(runtimeContext, "runtimeContext");
        try {
            call.requireCurrent();
        } catch (RuntimeException invalidContext) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.INVALID_CONTEXT,
                    "Coding filesystem context or Lease/Fencing ownership is no longer current",
                    invalidContext);
        }
    }

    private void beforeMutation(String operation, List<String> paths) {
        mutationHook.beforeFinalValidation(operation, List.copyOf(paths));
    }

    private void requireUnchanged(CodingFilesystemPathPolicy.ExistingTextFile expected) {
        expected.path().witness().verify();
        CodingFilesystemPathPolicy.ExistingTextFile current = pathPolicy.requireTextFile(
                expected.path().relative(), maximumSingleFileBytes);
        if (!current.content().equals(expected.content())) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.TOCTOU_DETECTED,
                    "Repository file changed before controlled mutation");
        }
    }

    private void verifyContent(String path, String expected) {
        CodingFilesystemPathPolicy.ExistingTextFile actual =
                pathPolicy.requireTextFile(path, maximumSingleFileBytes);
        if (!actual.content().equals(expected)) {
            throw filesystemFailure();
        }
    }

    private void overwrite(RuntimeContext runtimeContext, String containerPath, String content) {
        List<FileUploadResponse> responses = filesystem.uploadFiles(
                runtimeContext,
                List.of(java.util.Map.entry(
                        containerPath, content.getBytes(StandardCharsets.UTF_8))));
        if (responses.size() != 1 || !responses.get(0).isSuccess()) {
            throw filesystemFailure();
        }
    }

    private long requireResultSize(String content) {
        long bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maximumSingleFileBytes) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.BUDGET_EXCEEDED,
                    "Mutation result exceeds the effective single-file budget");
        }
        return bytes;
    }

    private void requireEditText(String oldText, String newText) {
        CodingFilesystemPathPolicy.requireText(oldText);
        CodingFilesystemPathPolicy.requireText(newText);
        requireToolInputSize(oldText);
        requireToolInputSize(newText);
        if (oldText.isEmpty() || oldText.equals(newText)) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.INVALID_REQUEST,
                    "Exact edit text must be non-empty and different from its replacement");
        }
    }

    private void requireToolInputSize(String content) {
        if (content.getBytes(StandardCharsets.UTF_8).length > maximumSingleFileBytes) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.BUDGET_EXCEEDED,
                    "Mutation input exceeds the effective single-file budget");
        }
    }

    private static int occurrences(String content, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = content.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }

    private static String replaceOnce(String content, String oldText, String newText) {
        int index = content.indexOf(oldText);
        return content.substring(0, index)
                + newText
                + content.substring(index + oldText.length());
    }

    private static String success(
            String status,
            String path,
            long bytes,
            CodingFilesystemUsage.UsageSnapshot usage) {
        return "status=" + status
                + " path=" + path
                + " bytes=" + bytes
                + " writeOperations=" + usage.writeOperations()
                + " writtenBytes=" + usage.writtenBytes()
                + " changedFiles=" + usage.changedFiles();
    }

    private static CodingFilesystemException filesystemFailure() {
        return new CodingFilesystemException(
                CodingFilesystemError.FILESYSTEM_FAILED,
                "Controlled AgentScope filesystem mutation failed");
    }
}
