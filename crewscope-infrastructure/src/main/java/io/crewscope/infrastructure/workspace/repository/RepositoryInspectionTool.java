package io.crewscope.infrastructure.workspace.repository;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Agent-callable, read-only repository inspection surface for one guarded Sandbox call. */
public final class RepositoryInspectionTool {

    private final TaskExecutionSandboxCall call;
    private final AbstractFilesystem filesystem;
    private final GitCommandExecutor gitCommands;
    private final ManagedWorktree worktree;
    private final ManagedRepository repository;
    private final RepositoryInspectionPathGuard pathGuard;
    private final io.crewscope.domain.coding.AllowedPathSet allowedPaths;
    private final int maximumPageSize;
    private final int maximumReadLines;
    private final int maximumTreeDepth;
    private final int maximumBackendOperations;
    private final int maximumResultBytes;

    RepositoryInspectionTool(
            TaskExecutionSandboxCall call,
            AbstractFilesystem filesystem,
            GitCommandExecutor gitCommands,
            ManagedWorktree worktree,
            ManagedRepository repository,
            RepositoryInspectionPathGuard pathGuard,
            io.crewscope.domain.coding.AllowedPathSet allowedPaths,
            int maximumPageSize,
            int maximumReadLines,
            int maximumTreeDepth,
            int maximumBackendOperations,
            int maximumResultBytes) {
        this.call = Objects.requireNonNull(call, "call");
        this.filesystem = Objects.requireNonNull(filesystem, "filesystem");
        this.gitCommands = Objects.requireNonNull(gitCommands, "gitCommands");
        this.worktree = Objects.requireNonNull(worktree, "worktree");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.pathGuard = Objects.requireNonNull(pathGuard, "pathGuard");
        this.allowedPaths = Objects.requireNonNull(allowedPaths, "allowedPaths");
        this.maximumPageSize = maximumPageSize;
        this.maximumReadLines = maximumReadLines;
        this.maximumTreeDepth = maximumTreeDepth;
        this.maximumBackendOperations = maximumBackendOperations;
        this.maximumResultBytes = maximumResultBytes;
    }

    @Tool(
            name = "repository_tree",
            readOnly = true,
            description = "List a bounded recursive tree below an allowed repository path.")
    public String tree(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "Canonical repository-relative directory")
                    String path,
            @ToolParam(name = "depth", description = "Recursive depth, starting at 1") int depth,
            @ToolParam(name = "offset", description = "Zero-based result offset") int offset,
            @ToolParam(name = "limit", description = "Maximum entries to return") int limit) {
        requireCall(runtimeContext);
        String root = pathGuard.requirePath(path);
        int acceptedDepth = requireRange(depth, 1, maximumTreeDepth, "Tree depth");
        PageRequest page = page(offset, limit);
        List<String> entries = new ArrayList<>();
        Deque<TreeDirectory> pending = new ArrayDeque<>();
        pending.add(new TreeDirectory(root, 0));
        int operations = 0;
        int desired = saturatedAdd(page.offset(), page.limit() + 1);
        while (!pending.isEmpty() && operations < maximumBackendOperations && entries.size() < desired) {
            TreeDirectory current = pending.removeFirst();
            LsResult result = filesystem.ls(
                    runtimeContext, pathGuard.toContainerPath(current.path()));
            operations++;
            if (!result.isSuccess()) {
                throw filesystemFailure("AgentScope could not list the requested repository path");
            }
            List<InspectedFile> children = inspectedFiles(result.entries());
            for (InspectedFile child : children) {
                entries.add(indent(current.level())
                        + (child.directory() ? "[DIR] " : "[FILE] ")
                        + child.path()
                        + (child.directory() ? "" : " (" + child.size() + " bytes)"));
                if (child.directory() && current.level() + 1 < acceptedDepth) {
                    pending.addLast(new TreeDirectory(child.path(), current.level() + 1));
                }
                if (entries.size() >= desired) {
                    break;
                }
            }
        }
        boolean traversalLimited = !pending.isEmpty()
                && operations >= maximumBackendOperations
                && entries.size() < desired;
        if (traversalLimited) {
            throw new RepositoryInspectionException(
                    RepositoryInspectionError.TRAVERSAL_LIMIT,
                    "Repository tree traversal exceeded its backend operation limit; inspect a"
                            + " narrower path or depth");
        }
        return render(entries, page, false);
    }

    @Tool(
            name = "repository_list",
            readOnly = true,
            description = "List direct children of an allowed repository directory.")
    public String list(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "Canonical repository-relative directory")
                    String path,
            @ToolParam(name = "offset", description = "Zero-based result offset") int offset,
            @ToolParam(name = "limit", description = "Maximum entries to return") int limit) {
        requireCall(runtimeContext);
        String relative = pathGuard.requirePath(path);
        PageRequest page = page(offset, limit);
        LsResult result = filesystem.ls(runtimeContext, pathGuard.toContainerPath(relative));
        if (!result.isSuccess()) {
            throw filesystemFailure("AgentScope could not list the requested repository path");
        }
        List<String> entries = inspectedFiles(result.entries()).stream()
                .map(file -> (file.directory() ? "[DIR] " : "[FILE] ")
                        + file.path()
                        + (file.directory() ? "" : " (" + file.size() + " bytes)"))
                .toList();
        return render(entries, page, false);
    }

    @Tool(
            name = "repository_read",
            readOnly = true,
            description = "Read a bounded page of UTF-8 text from an allowed repository file.")
    public String read(
            RuntimeContext runtimeContext,
            @ToolParam(name = "path", description = "Canonical repository-relative file")
                    String path,
            @ToolParam(name = "offset", description = "Zero-based line offset") int offset,
            @ToolParam(name = "limit", description = "Maximum lines to return") int limit) {
        requireCall(runtimeContext);
        String relative = pathGuard.requirePath(path);
        int acceptedOffset = requireNonNegative(offset, "Read offset");
        int acceptedLimit = requireRange(limit, 1, maximumReadLines, "Read limit");
        ReadResult result = filesystem.read(
                runtimeContext,
                pathGuard.toContainerPath(relative),
                acceptedOffset,
                acceptedLimit + 1);
        if (!result.isSuccess() || result.fileData() == null) {
            throw filesystemFailure("AgentScope could not read the requested repository file");
        }
        if (!"utf-8".equalsIgnoreCase(result.fileData().encoding())) {
            throw new RepositoryInspectionException(
                    RepositoryInspectionError.BINARY_FILE,
                    "Binary repository files are unavailable to inspection tools");
        }
        String content = Objects.requireNonNullElse(result.fileData().content(), "");
        requireTextContent(content);
        List<String> lines = splitLines(content);
        boolean hasMore = lines.size() > acceptedLimit;
        if (hasMore) {
            lines = lines.subList(0, acceptedLimit);
        }
        List<String> numbered = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            numbered.add((acceptedOffset + index + 1) + ":" + lines.get(index));
        }
        return renderWindow(numbered, acceptedOffset, acceptedLimit, hasMore);
    }

    @Tool(
            name = "repository_grep",
            readOnly = true,
            description = "Search literal text below an allowed repository directory.")
    public String grep(
            RuntimeContext runtimeContext,
            @ToolParam(name = "pattern", description = "Literal single-line search text")
                    String pattern,
            @ToolParam(name = "path", description = "Canonical repository-relative directory")
                    String path,
            @ToolParam(name = "glob", description = "Optional filename glob such as **/*.java")
                    String glob,
            @ToolParam(name = "offset", description = "Zero-based result offset") int offset,
            @ToolParam(name = "limit", description = "Maximum matches to return") int limit) {
        requireCall(runtimeContext);
        String relative = pathGuard.requirePath(path);
        String literal = pathGuard.requireLiteralPattern(pattern);
        String acceptedGlob = glob == null || glob.isBlank() ? null : pathGuard.requireGlobPattern(glob);
        PageRequest page = page(offset, limit);
        GrepResult result = filesystem.grep(
                runtimeContext,
                literal,
                pathGuard.toContainerPath(relative),
                acceptedGlob);
        if (!result.isSuccess()) {
            throw filesystemFailure("AgentScope could not search the requested repository path");
        }
        List<String> matches = new ArrayList<>();
        List<GrepMatch> grepMatches = result.matches() == null ? List.of() : result.matches();
        for (GrepMatch match : grepMatches) {
            Optional<String> pathResult = safeReturnedPath(match.path());
            if (pathResult.isPresent()) {
                String text = Objects.requireNonNullElse(match.text(), "");
                requireTextContent(text);
                matches.add(pathResult.orElseThrow() + ":" + match.line() + ":" + text);
            }
        }
        matches.sort(Comparator.naturalOrder());
        return render(matches, page, false);
    }

    @Tool(
            name = "repository_glob",
            readOnly = true,
            description = "Find files by filename glob below an allowed repository directory.")
    public String glob(
            RuntimeContext runtimeContext,
            @ToolParam(name = "pattern", description = "Filename glob, optionally prefixed by **/")
                    String pattern,
            @ToolParam(name = "path", description = "Canonical repository-relative directory")
                    String path,
            @ToolParam(name = "offset", description = "Zero-based result offset") int offset,
            @ToolParam(name = "limit", description = "Maximum matches to return") int limit) {
        requireCall(runtimeContext);
        String relative = pathGuard.requirePath(path);
        String acceptedPattern = pathGuard.requireGlobPattern(pattern);
        PageRequest page = page(offset, limit);
        GlobResult result = filesystem.glob(
                runtimeContext, acceptedPattern, pathGuard.toContainerPath(relative));
        if (!result.isSuccess()) {
            throw filesystemFailure("AgentScope could not glob the requested repository path");
        }
        List<String> matches = inspectedFiles(result.matches()).stream()
                .map(file -> file.path() + " (" + file.size() + " bytes)")
                .toList();
        return render(matches, page, false);
    }

    @Tool(
            name = "repository_git_history",
            readOnly = true,
            description = "Read a bounded Git history page for commits touching allowed paths.")
    public String gitHistory(
            RuntimeContext runtimeContext,
            @ToolParam(name = "offset", description = "Zero-based commit offset") int offset,
            @ToolParam(name = "limit", description = "Maximum commits to return") int limit) {
        requireCall(runtimeContext);
        PageRequest page = page(offset, limit);
        try {
            String output = gitCommands.inspectionLog(
                    repository.canonicalPath(),
                    worktree.headCommit(),
                    page.offset(),
                    page.limit() + 1,
                    allowedPaths);
            List<String> fields = splitNul(output);
            List<String> commits = new ArrayList<>();
            for (int index = 0; index + 3 < fields.size(); index += 4) {
                commits.add("commit=" + fields.get(index)
                        + " parents=" + fields.get(index + 1)
                        + " epoch=" + fields.get(index + 2)
                        + " subject=" + safeGitText(fields.get(index + 3)));
            }
            boolean hasMore = commits.size() > page.limit();
            if (hasMore) {
                commits = commits.subList(0, page.limit());
            }
            return renderWindow(commits, page.offset(), page.limit(), hasMore);
        } catch (RepositoryInspectionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw gitFailure(failure);
        }
    }

    @Tool(
            name = "repository_git_status",
            readOnly = true,
            description = "Read paged Git porcelain status restricted to allowed paths.")
    public String gitStatus(
            RuntimeContext runtimeContext,
            @ToolParam(name = "offset", description = "Zero-based status-entry offset") int offset,
            @ToolParam(name = "limit", description = "Maximum status entries to return") int limit) {
        requireCall(runtimeContext);
        PageRequest page = page(offset, limit);
        try {
            List<String> entries = parseStatus(gitCommands.inspectionStatus(
                    worktree.canonicalPath(), allowedPaths));
            return render(entries, page, false);
        } catch (RepositoryInspectionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw gitFailure(failure);
        }
    }

    @Tool(
            name = "repository_git_diff",
            readOnly = true,
            description = "Read a paged text Git diff from the immutable baseline for allowed paths.")
    public String gitDiff(
            RuntimeContext runtimeContext,
            @ToolParam(name = "offset", description = "Zero-based patch-line offset") int offset,
            @ToolParam(name = "limit", description = "Maximum patch lines to return") int limit) {
        requireCall(runtimeContext);
        PageRequest page = page(offset, limit);
        try {
            String output = gitCommands.inspectionDiff(
                    worktree.canonicalPath(), worktree.baselineCommit(), allowedPaths);
            requireTextContent(output);
            return render(splitLines(output), page, false);
        } catch (RepositoryInspectionException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw gitFailure(failure);
        }
    }

    private void requireCall(RuntimeContext runtimeContext) {
        Objects.requireNonNull(runtimeContext, "runtimeContext");
        try {
            call.requireCurrent();
        } catch (RuntimeException invalidContext) {
            throw new RepositoryInspectionException(
                    RepositoryInspectionError.INVALID_CONTEXT,
                    "Repository inspection context or Lease/Fencing ownership is no longer current",
                    invalidContext);
        }
    }

    private List<InspectedFile> inspectedFiles(List<FileInfo> files) {
        List<InspectedFile> inspected = new ArrayList<>();
        List<FileInfo> available = files == null ? List.of() : files;
        for (FileInfo file : available) {
            safeReturnedPath(file.path()).ifPresent(path -> inspected.add(
                    new InspectedFile(path, file.isDirectory(), file.size())));
        }
        inspected.sort(Comparator.comparing(InspectedFile::path));
        return List.copyOf(inspected);
    }

    private Optional<String> safeReturnedPath(String sandboxPath) {
        try {
            return Optional.of(pathGuard.requireReturnedPath(sandboxPath));
        } catch (RepositoryInspectionException failure) {
            if (failure.error() == RepositoryInspectionError.PATH_NOT_ALLOWED
                    || failure.error() == RepositoryInspectionError.SENSITIVE_PATH
                    || failure.error() == RepositoryInspectionError.SYMBOLIC_LINK) {
                return Optional.empty();
            }
            throw failure;
        }
    }

    private List<String> parseStatus(String output) {
        List<String> tokens = splitNul(output);
        List<String> entries = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.length() < 4 || token.charAt(2) != ' ') {
                throw gitFailure(null);
            }
            String code = token.substring(0, 2);
            String path = pathGuard.requirePath(token.substring(3));
            if (code.indexOf('R') >= 0 || code.indexOf('C') >= 0) {
                if (++index >= tokens.size()) {
                    throw gitFailure(null);
                }
                String source = pathGuard.requirePath(tokens.get(index));
                entries.add(code + " " + source + " -> " + path);
            } else {
                entries.add(code + " " + path);
            }
        }
        return List.copyOf(entries);
    }

    private String render(List<String> entries, PageRequest page, boolean forcedHasMore) {
        int start = Math.min(page.offset(), entries.size());
        int end = Math.min(entries.size(), saturatedAdd(start, page.limit()));
        boolean hasMore = forcedHasMore || end < entries.size();
        return renderWindow(entries.subList(start, end), page.offset(), page.limit(), hasMore);
    }

    private String renderWindow(
            List<String> selected, int logicalOffset, int requestedLimit, boolean initialHasMore) {
        List<String> accepted = new ArrayList<>(selected);
        boolean hasMore = initialHasMore;
        String result = pageText(accepted, logicalOffset, requestedLimit, hasMore);
        while (utf8Length(result) > maximumResultBytes && accepted.size() > 1) {
            accepted.remove(accepted.size() - 1);
            hasMore = true;
            result = pageText(accepted, logicalOffset, requestedLimit, true);
        }
        if (utf8Length(result) > maximumResultBytes && !accepted.isEmpty()) {
            String header = pageHeader(1, logicalOffset, requestedLimit, true);
            int available = Math.max(0, maximumResultBytes - utf8Length(header) - 1);
            accepted.set(0, truncateUtf8(accepted.get(0), available));
            result = header + "\n" + accepted.get(0);
        }
        if (utf8Length(result) > maximumResultBytes) {
            return truncateUtf8(result, maximumResultBytes);
        }
        return result;
    }

    private static String pageText(
            List<String> entries, int offset, int requestedLimit, boolean hasMore) {
        String header = pageHeader(entries.size(), offset, requestedLimit, hasMore);
        return entries.isEmpty() ? header : header + "\n" + String.join("\n", entries);
    }

    private static String pageHeader(
            int returned, int offset, int requestedLimit, boolean hasMore) {
        String next = hasMore ? Integer.toString(offset + returned) : "none";
        return "returned=" + returned
                + " offset=" + offset
                + " limit=" + requestedLimit
                + " hasMore=" + hasMore
                + " nextOffset=" + next;
    }

    private PageRequest page(int offset, int limit) {
        return new PageRequest(
                requireNonNegative(offset, "Page offset"),
                requireRange(limit, 1, maximumPageSize, "Page limit"));
    }

    private static int requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new RepositoryInspectionException(
                    RepositoryInspectionError.INVALID_REQUEST, name + " must not be negative");
        }
        return value;
    }

    private static int requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new RepositoryInspectionException(
                    RepositoryInspectionError.INVALID_REQUEST,
                    name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static void requireTextContent(String content) {
        if (content.codePoints().anyMatch(character ->
                character == 0 || (character < 0x20 && character != '\n' && character != '\r' && character != '\t'))) {
            throw new RepositoryInspectionException(
                    RepositoryInspectionError.BINARY_FILE,
                    "Binary repository content is unavailable to inspection tools");
        }
    }

    private static List<String> splitLines(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        return List.of(value.split("\\R", -1));
    }

    private static List<String> splitNul(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        String[] fields = value.split("\\u0000", -1);
        int length = fields.length;
        while (length > 0 && fields[length - 1].isEmpty()) {
            length--;
        }
        return List.of(java.util.Arrays.copyOf(fields, length));
    }

    private static String safeGitText(String value) {
        requireTextContent(value);
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String truncateUtf8(String value, int maximumBytes) {
        if (maximumBytes <= 0) {
            return "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maximumBytes) {
            return value;
        }
        int accepted = Math.min(maximumBytes, bytes.length);
        while (accepted > 0 && accepted < bytes.length && (bytes[accepted] & 0xC0) == 0x80) {
            accepted--;
        }
        return new String(bytes, 0, accepted, StandardCharsets.UTF_8);
    }

    private static int saturatedAdd(int left, int right) {
        long result = (long) left + right;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static String indent(int level) {
        return "  ".repeat(Math.max(0, level));
    }

    private static RepositoryInspectionException filesystemFailure(String message) {
        return new RepositoryInspectionException(
                RepositoryInspectionError.FILESYSTEM_FAILED, message);
    }

    private static RepositoryInspectionException gitFailure(Throwable cause) {
        return new RepositoryInspectionException(
                RepositoryInspectionError.GIT_FAILED,
                "Typed Git repository inspection failed",
                cause);
    }

    private record PageRequest(int offset, int limit) {}

    private record TreeDirectory(String path, int level) {}

    private record InspectedFile(String path, boolean directory, long size) {}
}
