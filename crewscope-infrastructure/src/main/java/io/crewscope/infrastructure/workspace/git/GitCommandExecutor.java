package io.crewscope.infrastructure.workspace.git;

import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.ManagedWorkspaceBranch;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.WorkspaceArchiveReference;
import io.crewscope.domain.action.RepositoryBranchReference;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Executes the finite set of host Git management operations used by CrewScope.
 *
 * <p>The class deliberately has no public raw-command method. Every argument comes from a fixed
 * template, a domain value object, or a normalized absolute path and is passed directly to
 * {@link ProcessBuilder} without a shell.
 */
public final class GitCommandExecutor {

    private static final String ZERO_OBJECT_ID = "0000000000000000000000000000000000000000";
    private static final int MAXIMUM_LOG_COMMITS = 1_000;
    private static final List<String> INSPECTION_EXCLUDED_PATHS = List.of(
            ":(exclude).env",
            ":(exclude)**/.env",
            ":(exclude).env.*",
            ":(exclude)**/.env.*",
            ":(exclude).ssh/**",
            ":(exclude)**/.ssh/**",
            ":(exclude).aws/**",
            ":(exclude)**/.aws/**",
            ":(exclude).gnupg/**",
            ":(exclude)**/.gnupg/**",
            ":(exclude).docker/**",
            ":(exclude)**/.docker/**",
            ":(exclude).npmrc",
            ":(exclude)**/.npmrc",
            ":(exclude).pypirc",
            ":(exclude)**/.pypirc",
            ":(exclude)id_rsa",
            ":(exclude)**/id_rsa",
            ":(exclude)id_ed25519",
            ":(exclude)**/id_ed25519",
            ":(exclude)credentials",
            ":(exclude)**/credentials",
            ":(exclude)credentials.json",
            ":(exclude)**/credentials.json",
            ":(exclude)**/*.pem",
            ":(exclude)**/*.key",
            ":(exclude)**/*.p12",
            ":(exclude)**/*.pfx",
            ":(exclude)**/*.jks",
            ":(exclude)**/*.keystore");

    private final GitProcessRunner processRunner;

    public GitCommandExecutor(GitCommandPolicy policy) {
        this(policy, "git");
    }

    /** Uses one deployment-selected absolute Git-compatible executable without invoking a shell. */
    public GitCommandExecutor(GitCommandPolicy policy, Path executable) {
        this(
                policy,
                Objects.requireNonNull(executable, "executable")
                        .toAbsolutePath()
                        .normalize()
                        .toString());
    }

    GitCommandExecutor(GitCommandPolicy policy, String executable) {
        this.processRunner = new GitProcessRunner(policy, executable);
    }

    /** Resolves one validated short branch to an immutable full commit identity. */
    public RepositoryCommitId resolveBranch(Path repository, RepositoryBranchName branch) {
        Path location = absolutePath(repository, "repository");
        RepositoryBranchName safeBranch = Objects.requireNonNull(branch, "branch");
        String revision = "refs/heads/" + safeBranch.value() + "^{commit}";
        String result = run(
                List.of("-C", location.toString(), "rev-parse", "--verify", "--end-of-options", revision),
                Optional.empty());
        return commitId(result);
    }

    /** Returns whether the supplied repository is a bare Git repository. */
    public boolean isBareRepository(Path repository) {
        Path location = absolutePath(repository, "repository");
        String result = run(
                List.of("-C", location.toString(), "rev-parse", "--is-bare-repository"),
                Optional.empty());
        return switch (result.trim()) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new GitCommandException(
                    GitCommandError.COMMAND_FAILED,
                    "Git returned an invalid repository type",
                    OptionalInt.empty());
        };
    }

    /** Verifies that one full immutable object identity still names a commit. */
    public void verifyCommit(Path repository, RepositoryCommitId commit) {
        Path location = absolutePath(repository, "repository");
        RepositoryCommitId immutableCommit = Objects.requireNonNull(commit, "commit");
        String revision = immutableCommit.value() + "^{commit}";
        RepositoryCommitId resolved = commitId(run(
                List.of(
                        "-C",
                        location.toString(),
                        "rev-parse",
                        "--verify",
                        "--end-of-options",
                        revision),
                Optional.empty()));
        if (!resolved.equals(immutableCommit)) {
            throw new GitCommandException(
                    GitCommandError.INVALID_REFERENCE,
                    "Git commit identity did not match the requested value",
                    OptionalInt.empty());
        }
    }

    /** Resolves a platform-managed branch when it exists. */
    public Optional<RepositoryCommitId> findManagedBranch(
            Path repository, ManagedWorkspaceBranch branch) {
        Path location = absolutePath(repository, "repository");
        ManagedWorkspaceBranch managedBranch = Objects.requireNonNull(branch, "branch");
        String revision = "refs/heads/" + managedBranch.value() + "^{commit}";
        try {
            return Optional.of(commitId(run(
                    List.of(
                            "-C",
                            location.toString(),
                            "rev-parse",
                            "--verify",
                            "--end-of-options",
                            revision),
                    Optional.empty())));
        } catch (GitCommandException failure) {
            if (failure.error() == GitCommandError.INVALID_REFERENCE) {
                return Optional.empty();
            }
            throw failure;
        }
    }

    /** Resolves a platform archive reference when it exists. */
    public Optional<RepositoryCommitId> findArchiveReference(
            Path repository, WorkspaceArchiveReference reference) {
        Path location = absolutePath(repository, "repository");
        WorkspaceArchiveReference archive = Objects.requireNonNull(reference, "reference");
        String revision = archive.value() + "^{commit}";
        try {
            return Optional.of(commitId(run(
                    List.of(
                            "-C",
                            location.toString(),
                            "rev-parse",
                            "--verify",
                            "--end-of-options",
                            revision),
                    Optional.empty())));
        } catch (GitCommandException failure) {
            if (failure.error() == GitCommandError.INVALID_REFERENCE) {
                return Optional.empty();
            }
            throw failure;
        }
    }

    /** Returns the immutable commit currently checked out by one managed Worktree. */
    public RepositoryCommitId headCommit(Path worktree) {
        Path location = absolutePath(worktree, "worktree");
        return commitId(run(
                List.of(
                        "-C",
                        location.toString(),
                        "rev-parse",
                        "--verify",
                        "--end-of-options",
                        "HEAD^{commit}"),
                Optional.empty()));
    }

    /** Verifies that a Worktree HEAD is attached to exactly the expected managed branch. */
    public boolean isCurrentBranch(Path worktree, ManagedWorkspaceBranch branch) {
        Path location = absolutePath(worktree, "worktree");
        ManagedWorkspaceBranch expected = Objects.requireNonNull(branch, "branch");
        String current = run(
                        List.of(
                                "-C",
                                location.toString(),
                                "symbolic-ref",
                                "--quiet",
                                "--short",
                                "HEAD"),
                        Optional.empty())
                .trim();
        return expected.value().equals(current);
    }

    /** Returns the absolute Git common directory used by one linked Worktree. */
    public Path commonDirectory(Path worktree) {
        Path location = absolutePath(worktree, "worktree");
        String output = run(
                        List.of(
                                "-C",
                                location.toString(),
                                "rev-parse",
                                "--path-format=absolute",
                                "--git-common-dir"),
                        Optional.empty())
                .trim();
        try {
            Path commonDirectory = Path.of(output).toAbsolutePath().normalize();
            if (!commonDirectory.isAbsolute()) {
                throw new IllegalArgumentException("Git common directory must be absolute");
            }
            return commonDirectory;
        } catch (RuntimeException invalidOutput) {
            throw new GitCommandException(
                    GitCommandError.COMMAND_FAILED,
                    "Git returned an invalid common directory",
                    OptionalInt.empty());
        }
    }

    /** Returns the immutable tree written by one commit. */
    public GitTreeId commitTreeId(Path repository, RepositoryCommitId commit) {
        Path location = absolutePath(repository, "repository");
        RepositoryCommitId immutableCommit = Objects.requireNonNull(commit, "commit");
        String revision = immutableCommit.value() + "^{tree}";
        return new GitTreeId(run(
                        List.of(
                                "-C",
                                location.toString(),
                                "rev-parse",
                                "--verify",
                                "--end-of-options",
                                revision),
                        Optional.empty())
                .trim());
    }

    /** Verifies the exact single-parent shape of a platform delivery commit. */
    public boolean hasSingleParent(
            Path repository, RepositoryCommitId commit, RepositoryCommitId expectedParent) {
        Path location = absolutePath(repository, "repository");
        RepositoryCommitId immutableCommit = Objects.requireNonNull(commit, "commit");
        RepositoryCommitId parent = Objects.requireNonNull(expectedParent, "expectedParent");
        String output = run(
                        List.of(
                                "-C",
                                location.toString(),
                                "rev-list",
                                "--parents",
                                "--max-count=1",
                                immutableCommit.value(),
                                "--"),
                        Optional.empty())
                .trim();
        return output.equals(immutableCommit.value() + " " + parent.value());
    }

    /** Creates a managed branch and linked Worktree at the supplied baseline commit. */
    public void addWorktree(
            Path repository,
            Path worktree,
            ManagedWorkspaceBranch branch,
            RepositoryCommitId baselineCommit) {
        Path repositoryPath = absolutePath(repository, "repository");
        Path worktreePath = absolutePath(worktree, "worktree");
        ManagedWorkspaceBranch managedBranch = Objects.requireNonNull(branch, "branch");
        RepositoryCommitId baseline = Objects.requireNonNull(baselineCommit, "baselineCommit");
        run(
                List.of(
                        "-C",
                        repositoryPath.toString(),
                        "worktree",
                        "add",
                        "-b",
                        managedBranch.value(),
                        worktreePath.toString(),
                        baseline.value()),
                Optional.empty());
    }

    /** Removes a linked Worktree; ownership and lifecycle checks are performed by M4-I03. */
    public void removeWorktree(Path repository, Path worktree) {
        Path repositoryPath = absolutePath(repository, "repository");
        Path worktreePath = absolutePath(worktree, "worktree");
        run(
                List.of(
                        "-C",
                        repositoryPath.toString(),
                        "worktree",
                        "remove",
                        "--force",
                        worktreePath.toString()),
                Optional.empty());
    }

    /** Prunes only Git's stale linked-Worktree administrative entries. */
    public void pruneWorktrees(Path repository) {
        Path repositoryPath = absolutePath(repository, "repository");
        run(
                List.of("-C", repositoryPath.toString(), "worktree", "prune"),
                Optional.empty());
    }

    /** Returns NUL-delimited porcelain status without invoking user aliases or a shell. */
    public String status(Path worktree) {
        Path location = absolutePath(worktree, "worktree");
        return run(
                        List.of(
                                "-C",
                                location.toString(),
                                "status",
                                "--porcelain=v1",
                                "-z",
                                "--untracked-files=all"),
                        Optional.empty());
    }

    /** Returns NUL-delimited status restricted to the effective inspection roots. */
    public String inspectionStatus(Path worktree, AllowedPathSet allowedPaths) {
        Path location = absolutePath(worktree, "worktree");
        List<String> arguments = new java.util.ArrayList<>(List.of(
                "-C",
                location.toString(),
                "status",
                "--porcelain=v1",
                "-z",
                "--untracked-files=all",
                "--"));
        arguments.addAll(inspectionPathspecs(allowedPaths));
        return run(arguments, Optional.empty());
    }

    /** Returns the binary-capable patch from the immutable baseline to the current Worktree. */
    public String diff(Path worktree, RepositoryCommitId baselineCommit) {
        Path location = absolutePath(worktree, "worktree");
        RepositoryCommitId baseline = Objects.requireNonNull(baselineCommit, "baselineCommit");
        return run(
                        List.of(
                                "-C",
                                location.toString(),
                                "diff",
                                "--binary",
                                "--no-ext-diff",
                                "--no-textconv",
                                "--find-renames",
                                baseline.value(),
                                "--"),
                        Optional.empty());
    }

    /** Returns NUL-delimited authoritative changed-path facts for a Worktree or commit pair. */
    public String diffNameStatus(
            Path repository,
            RepositoryCommitId baselineCommit,
            Optional<RepositoryCommitId> deliveryCommit) {
        List<String> arguments = diffArguments(
                repository,
                baselineCommit,
                deliveryCommit,
                List.of("--name-status", "-z", "--find-renames", "--find-copies"));
        arguments.add("--");
        return run(arguments, Optional.empty());
    }

    /** Returns NUL-delimited Git line statistics for exactly the supplied literal paths. */
    public String diffNumStat(
            Path repository,
            RepositoryCommitId baselineCommit,
            Optional<RepositoryCommitId> deliveryCommit,
            List<DiffPath> paths) {
        List<String> arguments = diffArguments(
                repository,
                baselineCommit,
                deliveryCommit,
                List.of("--numstat", "-z", "--find-renames", "--find-copies"));
        addLiteralPaths(arguments, paths);
        return run(arguments, Optional.empty());
    }

    /** Returns a binary-capable Patch for a Worktree/commit pair and optional literal paths. */
    public String diffPatch(
            Path repository,
            RepositoryCommitId baselineCommit,
            Optional<RepositoryCommitId> deliveryCommit,
            List<DiffPath> paths) {
        List<String> arguments = diffArguments(
                repository,
                baselineCommit,
                deliveryCommit,
                List.of(
                        "--binary",
                        "--no-ext-diff",
                        "--no-textconv",
                        "--find-renames",
                        "--find-copies",
                        "--unified=3"));
        addLiteralPaths(arguments, paths);
        return run(arguments, Optional.empty());
    }

    /**
     * Restores one verified delivery commit as staged changes over its immutable baseline.
     *
     * <p>This fixed two-command protocol is used only after FINALIZING recovery has verified the
     * Archive Ref and commit parent. HEAD remains on the managed baseline branch, so all existing
     * Worktree ownership and Diff invariants continue to apply.
     */
    public void restoreDeliveryChanges(
            Path worktree,
            RepositoryCommitId baselineCommit,
            RepositoryCommitId deliveryCommit) {
        Path location = absolutePath(worktree, "worktree");
        RepositoryCommitId baseline = Objects.requireNonNull(baselineCommit, "baselineCommit");
        RepositoryCommitId delivery = Objects.requireNonNull(deliveryCommit, "deliveryCommit");
        String patch = diffPatch(location, baseline, Optional.of(delivery), List.of());
        if (patch.isEmpty()) {
            return;
        }
        run(
                List.of(
                        "-C",
                        location.toString(),
                        "apply",
                        "--index",
                        "--binary",
                        "--whitespace=nowarn",
                        "-"),
                Optional.of(patch));
    }

    /** Produces an Added-file Patch for one untracked path without changing the Git index. */
    public String untrackedPatch(Path worktree, DiffPath path) {
        return runNoIndexDiff(
                worktree,
                path,
                List.of("--binary", "--no-ext-diff", "--no-textconv", "--unified=3"));
    }

    /** Produces Git line statistics for one untracked path without changing the Git index. */
    public String untrackedNumStat(Path worktree, DiffPath path) {
        return runNoIndexDiff(worktree, path, List.of("--numstat", "-z"));
    }

    /** Returns a text-only inspection patch restricted to the effective inspection roots. */
    public String inspectionDiff(
            Path worktree, RepositoryCommitId baselineCommit, AllowedPathSet allowedPaths) {
        Path location = absolutePath(worktree, "worktree");
        RepositoryCommitId baseline = Objects.requireNonNull(baselineCommit, "baselineCommit");
        List<String> arguments = new java.util.ArrayList<>(List.of(
                "-C",
                location.toString(),
                "diff",
                "--no-ext-diff",
                "--no-textconv",
                "--find-renames",
                baseline.value(),
                "--"));
        arguments.addAll(inspectionPathspecs(allowedPaths));
        return run(arguments, Optional.empty());
    }

    /** Returns a bounded, deterministic history projection starting at one immutable commit. */
    public String log(Path repository, RepositoryCommitId startCommit, int maximumCommits) {
        if (maximumCommits < 1 || maximumCommits > MAXIMUM_LOG_COMMITS) {
            throw new IllegalArgumentException("Git log limit must be between 1 and 1000");
        }
        Path location = absolutePath(repository, "repository");
        RepositoryCommitId start = Objects.requireNonNull(startCommit, "startCommit");
        return run(
                        List.of(
                                "-C",
                                location.toString(),
                                "log",
                                "--no-decorate",
                                "--format=%H%x00%P%x00%ct%x00%s%x00",
                                "--max-count=" + maximumCommits,
                                start.value(),
                                "--"),
                        Optional.empty());
    }

    /** Returns a paged history projection restricted to commits touching inspection roots. */
    public String inspectionLog(
            Path repository,
            RepositoryCommitId startCommit,
            int skip,
            int maximumCommits,
            AllowedPathSet allowedPaths) {
        if (skip < 0) {
            throw new IllegalArgumentException("Git log offset must not be negative");
        }
        if (maximumCommits < 1 || maximumCommits > MAXIMUM_LOG_COMMITS + 1) {
            throw new IllegalArgumentException("Inspection Git log limit must be between 1 and 1001");
        }
        Path location = absolutePath(repository, "repository");
        RepositoryCommitId start = Objects.requireNonNull(startCommit, "startCommit");
        List<String> arguments = new java.util.ArrayList<>(List.of(
                "-C",
                location.toString(),
                "log",
                "--no-decorate",
                "--format=%H%x00%P%x00%ct%x00%s%x00",
                "--skip=" + skip,
                "--max-count=" + maximumCommits,
                start.value(),
                "--"));
        arguments.addAll(inspectionPathspecs(allowedPaths));
        return run(arguments, Optional.empty());
    }

    /** Reads one repository-relative text blob from an immutable commit. */
    public String show(Path repository, RepositoryCommitId commit, DiffPath path) {
        Path location = absolutePath(repository, "repository");
        RepositoryCommitId immutableCommit = Objects.requireNonNull(commit, "commit");
        DiffPath relativePath = Objects.requireNonNull(path, "path");
        String object = immutableCommit.value() + ":" + relativePath.value();
        return run(
                        List.of("-C", location.toString(), "show", "--end-of-options", object),
                        Optional.empty());
    }

    /** Stages the complete Worktree through a fixed {@code git add --all} template. */
    public void stageAll(Path worktree) {
        Path location = absolutePath(worktree, "worktree");
        run(List.of("-C", location.toString(), "add", "--all", "--"), Optional.empty());
    }

    /** Writes the current index and returns its immutable tree identity. */
    public GitTreeId writeTree(Path worktree) {
        Path location = absolutePath(worktree, "worktree");
        return new GitTreeId(
                run(List.of("-C", location.toString(), "write-tree"), Optional.empty()).trim());
    }

    /** Creates a delivery commit without moving the active managed branch. */
    public RepositoryCommitId commitTree(
            Path repository,
            GitTreeId tree,
            RepositoryCommitId parent,
            GitCommitMessage message) {
        Path location = absolutePath(repository, "repository");
        GitTreeId immutableTree = Objects.requireNonNull(tree, "tree");
        RepositoryCommitId parentCommit = Objects.requireNonNull(parent, "parent");
        GitCommitMessage commitMessage = Objects.requireNonNull(message, "message");
        return commitId(run(
                        List.of(
                                "-C",
                                location.toString(),
                                "commit-tree",
                                immutableTree.value(),
                                "-p",
                                parentCommit.value()),
                        Optional.of(commitMessage.value() + "\n")));
    }

    /** Atomically creates an archive reference and refuses to overwrite an existing value. */
    public void createArchiveReference(
            Path repository,
            WorkspaceArchiveReference reference,
            RepositoryCommitId deliveryCommit) {
        Path location = absolutePath(repository, "repository");
        WorkspaceArchiveReference archive = Objects.requireNonNull(reference, "reference");
        RepositoryCommitId commit = Objects.requireNonNull(deliveryCommit, "deliveryCommit");
        run(
                List.of(
                        "-C",
                        location.toString(),
                        "update-ref",
                        archive.value(),
                        commit.value(),
                        ZERO_OBJECT_ID),
                Optional.empty());
    }

    /** Deletes exactly one platform-generated managed branch at an expected commit. */
    public void deleteManagedBranch(
            Path repository,
            ManagedWorkspaceBranch branch,
            RepositoryCommitId expectedCommit) {
        Path location = absolutePath(repository, "repository");
        ManagedWorkspaceBranch managedBranch = Objects.requireNonNull(branch, "branch");
        RepositoryCommitId expected = Objects.requireNonNull(expectedCommit, "expectedCommit");
        run(
                List.of(
                        "-C",
                        location.toString(),
                        "update-ref",
                        "-d",
                        "refs/heads/" + managedBranch.value(),
                        expected.value()),
                Optional.empty());
    }

    /** Initializes one platform-selected path as a bare repository. */
    public void initializeBareRepository(Path repository) {
        Path location = absolutePath(repository, "repository");
        Path parent = Objects.requireNonNull(location.getParent(), "repository parent");
        run(
                List.of(
                        "-C",
                        parent.toString(),
                        "init",
                        "--bare",
                        "--initial-branch=main",
                        location.getFileName().toString()),
                Optional.empty());
    }

    /** Sets one validated baseline branch in a managed bare repository. */
    public void updateBranchRef(
            Path repository, RepositoryBranchName branch, RepositoryCommitId commit) {
        Path location = absolutePath(repository, "repository");
        RepositoryBranchName safeBranch = Objects.requireNonNull(branch, "branch");
        RepositoryCommitId safeCommit = Objects.requireNonNull(commit, "commit");
        run(List.of(
                "-C", location.toString(), "update-ref",
                "refs/heads/" + safeBranch.value(), safeCommit.value()), Optional.empty());
    }

    /** Imports an exact immutable commit from a trusted managed local repository. */
    public void fetchLocalCommit(
            Path mirror, Path sourceRepository, RepositoryCommitId commit) {
        Path target = absolutePath(mirror, "mirror");
        Path source = absolutePath(sourceRepository, "sourceRepository");
        RepositoryCommitId immutableCommit = Objects.requireNonNull(commit, "commit");
        run(
                List.of(
                        "-C",
                        target.toString(),
                        "fetch",
                        "--no-tags",
                        "--no-write-fetch-head",
                        source.toString(),
                        immutableCommit.value()),
                Optional.empty());
    }

    /** Reads one exact remote branch without persisting a credential or Remote config. */
    public Optional<RepositoryCommitId> findRemoteBranchHead(
            Path mirror,
            URI remote,
            RepositoryBranchReference branch,
            GitAskPassEnvironment askPassEnvironment) {
        Path target = absolutePath(mirror, "mirror");
        String remoteValue = remote(remote);
        RepositoryBranchReference fullBranch = Objects.requireNonNull(branch, "branch");
        GitProcessRunner.GitProcessResult result = processRunner.runForResult(
                List.of(
                        "-c",
                        "credential.helper=",
                        "-c",
                        "http.followRedirects=false",
                        "-C",
                        target.toString(),
                        "ls-remote",
                        "--exit-code",
                        "--refs",
                        remoteValue,
                        fullBranch.value()),
                Optional.empty(),
                Set.of(0, 2),
                Objects.requireNonNull(askPassEnvironment, "askPassEnvironment"));
        if (result.exitCode() == 2) {
            return Optional.empty();
        }
        String output = result.output().trim();
        int separator = output.indexOf('\t');
        if (separator < 0 || output.indexOf('\n') >= 0
                || !output.substring(separator + 1).equals(fullBranch.value())) {
            throw new GitCommandException(
                    GitCommandError.COMMAND_FAILED,
                    "Git returned an invalid remote branch identity",
                    OptionalInt.empty());
        }
        return Optional.of(commitId(output.substring(0, separator)));
    }

    /** Fetches the current exact remote branch objects without writing a configured Remote. */
    public void fetchRemoteBranch(
            Path mirror,
            URI remote,
            RepositoryBranchReference branch,
            GitAskPassEnvironment askPassEnvironment) {
        Path target = absolutePath(mirror, "mirror");
        RepositoryBranchReference fullBranch = Objects.requireNonNull(branch, "branch");
        processRunner.run(
                List.of(
                        "-c",
                        "credential.helper=",
                        "-c",
                        "http.followRedirects=false",
                        "-C",
                        target.toString(),
                        "fetch",
                        "--no-tags",
                        "--no-write-fetch-head",
                        remote(remote),
                        fullBranch.value()),
                Optional.empty(),
                Objects.requireNonNull(askPassEnvironment, "askPassEnvironment"));
    }

    /** Returns whether one immutable commit is an ancestor of another. */
    public boolean isAncestor(
            Path repository, RepositoryCommitId ancestor, RepositoryCommitId descendant) {
        Path location = absolutePath(repository, "repository");
        RepositoryCommitId base = Objects.requireNonNull(ancestor, "ancestor");
        RepositoryCommitId head = Objects.requireNonNull(descendant, "descendant");
        GitProcessRunner.GitProcessResult result = processRunner.runForResult(
                List.of(
                        "-C",
                        location.toString(),
                        "merge-base",
                        "--is-ancestor",
                        base.value(),
                        head.value()),
                Optional.empty(),
                Set.of(0, 1));
        return result.exitCode() == 0;
    }

    /** Pushes one full SHA RefSpec guarded by an exact atomic force-with-lease. */
    public void pushBranch(
            Path mirror,
            URI remote,
            RepositoryBranchReference branch,
            RepositoryCommitId deliveryHead,
            Optional<RepositoryCommitId> expectedRemoteHead,
            GitAskPassEnvironment askPassEnvironment) {
        Path target = absolutePath(mirror, "mirror");
        RepositoryBranchReference fullBranch = Objects.requireNonNull(branch, "branch");
        RepositoryCommitId delivery = Objects.requireNonNull(deliveryHead, "deliveryHead");
        Optional<RepositoryCommitId> expected = Objects.requireNonNull(
                expectedRemoteHead, "expectedRemoteHead");
        String lease = "--force-with-lease=" + fullBranch.value() + ":"
                + expected.map(RepositoryCommitId::value).orElse("");
        processRunner.run(
                List.of(
                        "-c",
                        "credential.helper=",
                        "-c",
                        "http.followRedirects=false",
                        "-C",
                        target.toString(),
                        "push",
                        "--porcelain",
                        lease,
                        remote(remote),
                        delivery.value() + ":" + fullBranch.value()),
                Optional.empty(),
                Objects.requireNonNull(askPassEnvironment, "askPassEnvironment"));
    }

    private RepositoryCommitId commitId(String output) {
        String normalized = output.trim();
        try {
            return new RepositoryCommitId(normalized);
        } catch (RuntimeException invalidOutput) {
            throw new GitCommandException(
                    GitCommandError.COMMAND_FAILED,
                    "Git returned an invalid object identity",
                    OptionalInt.empty(),
                    invalidOutput);
        }
    }

    private String run(List<String> arguments, Optional<String> standardInput) {
        return processRunner.run(arguments, standardInput);
    }

    private String runNoIndexDiff(Path worktree, DiffPath path, List<String> options) {
        Path location = absolutePath(worktree, "worktree");
        DiffPath relativePath = Objects.requireNonNull(path, "path");
        List<String> arguments = new java.util.ArrayList<>();
        arguments.add("-C");
        arguments.add(location.toString());
        arguments.add("diff");
        arguments.add("--no-index");
        arguments.addAll(options);
        arguments.add("--");
        arguments.add("/dev/null");
        arguments.add(relativePath.value());
        // Git documents exit code 1 as "differences found" for --no-index.
        return processRunner.run(arguments, Optional.empty(), Set.of(0, 1));
    }

    private static List<String> diffArguments(
            Path repository,
            RepositoryCommitId baselineCommit,
            Optional<RepositoryCommitId> deliveryCommit,
            List<String> options) {
        Path location = absolutePath(repository, "repository");
        RepositoryCommitId baseline = Objects.requireNonNull(baselineCommit, "baselineCommit");
        Optional<RepositoryCommitId> delivery = Objects.requireNonNull(
                deliveryCommit, "deliveryCommit");
        List<String> arguments = new java.util.ArrayList<>();
        arguments.add("-C");
        arguments.add(location.toString());
        arguments.add("diff");
        arguments.addAll(options);
        arguments.add(baseline.value());
        delivery.ifPresent(commit -> arguments.add(commit.value()));
        return arguments;
    }

    private static void addLiteralPaths(List<String> arguments, List<DiffPath> paths) {
        arguments.add("--");
        List<DiffPath> requiredPaths = List.copyOf(Objects.requireNonNull(paths, "paths"));
        requiredPaths.forEach(path -> arguments.add(
                Objects.requireNonNull(path, "path").value()));
    }

    private static List<String> inspectionPathspecs(AllowedPathSet allowedPaths) {
        List<String> pathspecs = new java.util.ArrayList<>();
        Objects.requireNonNull(allowedPaths, "allowedPaths").values().stream()
                .map(GitCommandExecutor::literalTopPathspec)
                .forEach(pathspecs::add);
        pathspecs.addAll(INSPECTION_EXCLUDED_PATHS);
        return List.copyOf(pathspecs);
    }

    /** Prevents repository path names beginning with ':' from being parsed as Git pathspec magic. */
    private static String literalTopPathspec(String path) {
        return ".".equals(path) ? ":(top,glob)**" : ":(top,literal)" + path;
    }

    private static Path absolutePath(Path path, String name) {
        Path normalized = Objects.requireNonNull(path, name).toAbsolutePath().normalize();
        if (!normalized.isAbsolute()) {
            throw new IllegalArgumentException(name + " must resolve to an absolute path");
        }
        return normalized;
    }

    private static String remote(URI remote) {
        URI value = Objects.requireNonNull(remote, "remote");
        if (value.getUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw new IllegalArgumentException("Git remote must not contain credentials or query data");
        }
        return value.toASCIIString();
    }

}
