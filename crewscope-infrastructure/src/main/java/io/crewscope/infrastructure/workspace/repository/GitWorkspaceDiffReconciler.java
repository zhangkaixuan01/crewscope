package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.DiffFileKind;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.infrastructure.workspace.git.GitCommandException;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Recomputes Workspace Diff facts from fixed Git commands; file events never become authority. */
public final class GitWorkspaceDiffReconciler {

    private final GitCommandExecutor git;
    private final int previewBytes;
    private final int previewLines;

    GitWorkspaceDiffReconciler(GitCommandExecutor git, WorkspaceDiffProperties properties) {
        this.git = Objects.requireNonNull(git, "git");
        WorkspaceDiffProperties configured = Objects.requireNonNull(properties, "properties");
        configured.validate();
        this.previewBytes = configured.getPatchPreviewBytes();
        this.previewLines = configured.getPatchPreviewLines();
    }

    /** Reconciles baseline to index/Worktree and also includes untracked files without staging. */
    public WorkspaceDiffSnapshot reconcileWorkingTree(
            ExecutionWorkspace workspace,
            ManagedWorktree worktree,
            WorkspacePolicy policy,
            Optional<DiffManifest> previous) {
        ExecutionWorkspace current = requireLiveContext(workspace, worktree, policy);
        return reconcile(
                worktree.canonicalPath(),
                current.baselineCommit(),
                Optional.empty(),
                policy,
                Objects.requireNonNull(previous, "previous"),
                true);
    }

    /** Recomputes terminal facts from immutable commits and never reads mutable Worktree state. */
    WorkspaceDiffSnapshot reconcileCommits(
            ExecutionWorkspace workspace,
            ManagedRepository repository,
            WorkspacePolicy policy,
            RepositoryCommitId deliveryCommit,
            Optional<DiffManifest> previous) {
        ExecutionWorkspace current = requirePolicyContext(workspace, policy);
        ManagedRepository managed = Objects.requireNonNull(repository, "repository");
        if (!current.repositoryKey().equals(managed.repositoryKey())) {
            throw failure(WorkspaceDiffError.INVALID_CONTEXT, "Repository does not match Workspace");
        }
        return reconcile(
                managed.canonicalPath(),
                current.baselineCommit(),
                Optional.of(Objects.requireNonNull(deliveryCommit, "deliveryCommit")),
                policy,
                Objects.requireNonNull(previous, "previous"),
                false);
    }

    WorkspaceDiffSnapshot reconcile(
            Path repository,
            RepositoryCommitId baseline,
            Optional<RepositoryCommitId> delivery,
            WorkspacePolicy policy,
            Optional<DiffManifest> previous,
            boolean includeUntracked) {
        try {
            List<ChangedPath> changed = parseNameStatus(
                    git.diffNameStatus(repository, baseline, delivery));
            Set<DiffPath> trackedPaths = new LinkedHashSet<>();
            changed.forEach(change -> {
                change.oldPath().ifPresent(trackedPaths::add);
                trackedPaths.add(change.path());
            });
            List<DiffPath> untracked = includeUntracked
                    ? parseUntracked(git.status(repository), trackedPaths)
                    : List.of();
            requirePolicyPaths(policy, changed, untracked);
            requireFileBudget(policy.operationBudget(), changed.size() + untracked.size());

            List<DiffFileEntry> files = new ArrayList<>(changed.size() + untracked.size());
            String trackedPatch = git.diffPatch(repository, baseline, delivery, List.of());
            for (ChangedPath change : changed) {
                List<DiffPath> paths = change.oldPath()
                        .map(old -> List.of(old, change.path()))
                        .orElseGet(() -> List.of(change.path()));
                String patch = git.diffPatch(repository, baseline, delivery, paths);
                requireSinglePatchBudget(policy.operationBudget(), patch);
                LineStatistics statistics = parseNumStat(
                        git.diffNumStat(repository, baseline, delivery, paths));
                files.add(entry(change, statistics, patch));
            }

            StringBuilder fullPatch = new StringBuilder(trackedPatch);
            for (DiffPath path : untracked) {
                String patch = git.untrackedPatch(repository, path);
                requireSinglePatchBudget(policy.operationBudget(), patch);
                LineStatistics statistics = parseNumStat(git.untrackedNumStat(repository, path));
                files.add(entry(
                        new ChangedPath(path, Optional.empty(), DiffFileKind.ADDED),
                        statistics,
                        patch));
                fullPatch.append(patch);
            }
            requireDiffBudget(policy.operationBudget(), fullPatch.toString());
            DiffManifest manifest = previous
                    .map(value -> DiffManifest.reconcile(value, files))
                    .orElseGet(() -> DiffManifest.capture(DiffGeneration.first(), files));
            return new WorkspaceDiffSnapshot(manifest, fullPatch.toString());
        } catch (WorkspaceDiffException failure) {
            throw failure;
        } catch (GitCommandException failure) {
            WorkspaceDiffError error = switch (failure.error()) {
                case OUTPUT_LIMIT -> WorkspaceDiffError.DIFF_LIMIT_EXCEEDED;
                default -> WorkspaceDiffError.COMMAND_FAILED;
            };
            throw failure(error, "Git Diff reconciliation failed");
        } catch (RuntimeException failure) {
            throw failure(
                    WorkspaceDiffError.INVALID_GIT_OUTPUT,
                    "Git returned invalid Diff facts");
        }
    }

    private DiffFileEntry entry(
            ChangedPath change, LineStatistics statistics, String patch) {
        RuntimeContentHash hash = RuntimeContentHash.sha256(patch);
        if (statistics.binary()) {
            return DiffFileEntry.binary(
                    change.path().value(),
                    change.oldPath().map(DiffPath::value),
                    change.kind(),
                    hash);
        }
        PatchPreview preview = preview(patch);
        return DiffFileEntry.text(
                change.path().value(),
                change.oldPath().map(DiffPath::value),
                change.kind(),
                statistics.additions(),
                statistics.deletions(),
                preview.truncated(),
                hash,
                Optional.of(preview.content()));
    }

    private PatchPreview preview(String patch) {
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        int lines = 0;
        int offset = 0;
        while (offset < patch.length()) {
            int lineEnd = patch.indexOf('\n', offset);
            int segmentEnd = lineEnd < 0 ? patch.length() : lineEnd + 1;
            String segment = patch.substring(offset, segmentEnd);
            int segmentBytes = segment.getBytes(StandardCharsets.UTF_8).length;
            if (lines >= previewLines || bytes + segmentBytes > previewBytes) {
                break;
            }
            result.append(segment);
            bytes += segmentBytes;
            lines++;
            offset = segmentEnd;
        }
        return new PatchPreview(result.toString(), offset < patch.length());
    }

    private static List<ChangedPath> parseNameStatus(String output) {
        String[] tokens = Objects.requireNonNull(output, "output").split("\\u0000", -1);
        List<ChangedPath> changes = new ArrayList<>();
        int index = 0;
        while (index < tokens.length && !tokens[index].isEmpty()) {
            String status = tokens[index++];
            if (status.isEmpty()) {
                break;
            }
            DiffFileKind kind = kind(status.charAt(0));
            if (kind == DiffFileKind.RENAMED || kind == DiffFileKind.COPIED) {
                requireRemaining(tokens, index, 2);
                DiffPath oldPath = path(tokens[index++]);
                changes.add(new ChangedPath(path(tokens[index++]), Optional.of(oldPath), kind));
            } else {
                requireRemaining(tokens, index, 1);
                changes.add(new ChangedPath(path(tokens[index++]), Optional.empty(), kind));
            }
        }
        return List.copyOf(changes);
    }

    private static List<DiffPath> parseUntracked(String output, Set<DiffPath> trackedPaths) {
        String[] tokens = Objects.requireNonNull(output, "output").split("\\u0000", -1);
        Set<DiffPath> untracked = new LinkedHashSet<>();
        for (int index = 0; index < tokens.length; index++) {
            String token = tokens[index];
            if (token.isEmpty()) {
                continue;
            }
            if (token.length() < 3) {
                throw failure(WorkspaceDiffError.INVALID_GIT_OUTPUT, "Git status is malformed");
            }
            String code = token.substring(0, 2);
            DiffPath current = path(token.substring(3));
            if ("??".equals(code) && !trackedPaths.contains(current)) {
                untracked.add(current);
            }
            if ((code.indexOf('R') >= 0 || code.indexOf('C') >= 0)
                    && index + 1 < tokens.length) {
                index++;
            }
        }
        return untracked.stream().sorted().toList();
    }

    private static LineStatistics parseNumStat(String output) {
        String first = Objects.requireNonNull(output, "output").split("\\u0000", -1)[0];
        String[] fields = first.split("\\t", -1);
        if (fields.length < 2) {
            throw failure(WorkspaceDiffError.INVALID_GIT_OUTPUT, "Git line statistics are malformed");
        }
        if ("-".equals(fields[0]) && "-".equals(fields[1])) {
            return new LineStatistics(0, 0, true);
        }
        try {
            return new LineStatistics(
                    Long.parseLong(fields[0]), Long.parseLong(fields[1]), false);
        } catch (NumberFormatException invalid) {
            throw failure(WorkspaceDiffError.INVALID_GIT_OUTPUT, "Git line statistics are invalid");
        }
    }

    private static void requirePolicyPaths(
            WorkspacePolicy policy, List<ChangedPath> changed, List<DiffPath> untracked) {
        boolean outside = changed.stream().anyMatch(change ->
                        !policy.allowedPaths().allows(change.path().value())
                                || change.oldPath()
                                        .filter(old -> !policy.allowedPaths().allows(old.value()))
                                        .isPresent())
                || untracked.stream()
                        .anyMatch(path -> !policy.allowedPaths().allows(path.value()));
        if (outside) {
            throw failure(
                    WorkspaceDiffError.PATH_OUTSIDE_POLICY,
                    "Workspace contains a change outside AllowedPaths");
        }
    }

    private static void requireFileBudget(WorkspaceOperationBudget budget, int files) {
        if (files > budget.maxChangedFiles()) {
            throw failure(
                    WorkspaceDiffError.DIFF_LIMIT_EXCEEDED,
                    "Workspace changed-file budget was exceeded");
        }
    }

    private static void requireDiffBudget(WorkspaceOperationBudget budget, String patch) {
        if (patch.getBytes(StandardCharsets.UTF_8).length > budget.maxDiffBytes()) {
            throw failure(
                    WorkspaceDiffError.DIFF_LIMIT_EXCEEDED,
                    "Workspace Diff byte budget was exceeded");
        }
    }

    private static void requireSinglePatchBudget(
            WorkspaceOperationBudget budget, String patch) {
        if (patch.getBytes(StandardCharsets.UTF_8).length > budget.maxSingleFileBytes()) {
            throw failure(
                    WorkspaceDiffError.DIFF_LIMIT_EXCEEDED,
                    "Single-file Patch byte budget was exceeded");
        }
    }

    private static ExecutionWorkspace requireLiveContext(
            ExecutionWorkspace workspace, ManagedWorktree worktree, WorkspacePolicy policy) {
        ExecutionWorkspace current = requirePolicyContext(workspace, policy);
        ManagedWorktree managed = Objects.requireNonNull(worktree, "worktree");
        if (!current.id().equals(managed.workspaceId())
                || !current.repositoryKey().equals(managed.repositoryKey())
                || !current.workspaceKey().equals(managed.workspaceKey())
                || !current.managedBranch().equals(managed.managedBranch())
                || !current.baselineCommit().equals(managed.baselineCommit())
                || !current.baselineCommit().equals(managed.headCommit())) {
            throw failure(WorkspaceDiffError.INVALID_CONTEXT, "Worktree does not match Workspace");
        }
        return current;
    }

    private static ExecutionWorkspace requirePolicyContext(
            ExecutionWorkspace workspace, WorkspacePolicy policy) {
        ExecutionWorkspace current = Objects.requireNonNull(workspace, "workspace");
        WorkspacePolicy effective = Objects.requireNonNull(policy, "policy");
        if (!current.scope().equals(effective.scope())
                || !current.taskId().equals(effective.taskId())
                || !current.taskExecutionId().equals(effective.taskExecutionId())
                || current.attempt() != effective.attempt()
                || !current.codingTarget().equals(effective.codingTarget())) {
            throw failure(WorkspaceDiffError.INVALID_CONTEXT, "Policy does not match Workspace");
        }
        return current;
    }

    private static DiffPath path(String value) {
        if (value.indexOf('\uFFFD') >= 0) {
            throw failure(WorkspaceDiffError.INVALID_GIT_OUTPUT, "Git path is not valid UTF-8");
        }
        return new DiffPath(value);
    }

    private static DiffFileKind kind(char code) {
        return switch (code) {
            case 'A' -> DiffFileKind.ADDED;
            case 'M' -> DiffFileKind.MODIFIED;
            case 'D' -> DiffFileKind.DELETED;
            case 'R' -> DiffFileKind.RENAMED;
            case 'C' -> DiffFileKind.COPIED;
            case 'T' -> DiffFileKind.TYPE_CHANGED;
            default -> throw failure(
                    WorkspaceDiffError.INVALID_GIT_OUTPUT,
                    "Git returned an unsupported change kind");
        };
    }

    private static void requireRemaining(String[] tokens, int index, int count) {
        if (index + count > tokens.length) {
            throw failure(WorkspaceDiffError.INVALID_GIT_OUTPUT, "Git changed-path output is malformed");
        }
    }

    private static WorkspaceDiffException failure(WorkspaceDiffError error, String message) {
        return new WorkspaceDiffException(error, message);
    }

    private record ChangedPath(
            DiffPath path, Optional<DiffPath> oldPath, DiffFileKind kind) {

        private ChangedPath {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(oldPath, "oldPath");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private record LineStatistics(long additions, long deletions, boolean binary) {}

    private record PatchPreview(String content, boolean truncated) {}
}
