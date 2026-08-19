package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ManagedWorktreeLocator;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.infrastructure.workspace.git.GitCommandError;
import io.crewscope.infrastructure.workspace.git.GitCommandException;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.git.GitCommitMessage;
import io.crewscope.infrastructure.workspace.git.GitTreeId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns creation, verification, orphan rollback and local delivery of managed Git Worktrees.
 *
 * <p>PostgreSQL {@link ExecutionWorkspace} facts remain authoritative. This adapter persists no
 * second metadata registry and proves every physical resource again while holding the same path
 * lock used by Provision, recovery and archive operations.
 */
public final class WorktreeProvisioner {

    private static final LinkOption[] NO_FOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

    private final Path canonicalWorktreeRoot;
    private final String requiredOwner;
    private final ManagedRepositoryResolver repositoryResolver;
    private final GitCommandExecutor gitCommands;
    private final WorkspacePathLockManager lockManager;
    private final WorktreeStageHook stageHook;

    public WorktreeProvisioner(
            Path worktreeRoot,
            String requiredOwner,
            ManagedRepositoryResolver repositoryResolver,
            GitCommandExecutor gitCommands,
            WorkspacePathLockManager lockManager) {
        this(
                worktreeRoot,
                requiredOwner,
                repositoryResolver,
                gitCommands,
                lockManager,
                WorktreeStageHook.NONE);
    }

    WorktreeProvisioner(
            Path worktreeRoot,
            String requiredOwner,
            ManagedRepositoryResolver repositoryResolver,
            GitCommandExecutor gitCommands,
            WorkspacePathLockManager lockManager,
            WorktreeStageHook stageHook) {
        this.canonicalWorktreeRoot = canonicalRoot(worktreeRoot);
        this.requiredOwner = requireOwner(requiredOwner);
        this.repositoryResolver = Objects.requireNonNull(repositoryResolver, "repositoryResolver");
        this.gitCommands = Objects.requireNonNull(gitCommands, "gitCommands");
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager");
        this.stageHook = Objects.requireNonNull(stageHook, "stageHook");
        requireOwner(this.canonicalWorktreeRoot);
    }

    /** Creates or idempotently recovers the exact Worktree described by durable facts. */
    public ManagedWorktree provision(ExecutionWorkspace workspace, WorkspacePolicy policy) {
        ExecutionWorkspace requiredWorkspace = requirePolicyLineage(workspace, policy);
        try (WorkspacePathLock ignored = lockManager.tryAcquire(requiredWorkspace.worktreeLocator())) {
            ManagedRepository repository = repositoryResolver.resolve(
                    requiredWorkspace.repositoryKey());
            Path candidate = resolveCandidate(requiredWorkspace.worktreeLocator());
            if (existsNoFollow(candidate)) {
                return verifyLocked(requiredWorkspace, policy, repository, candidate);
            }
            if (findArchive(repository, requiredWorkspace).isPresent()) {
                throw failure(
                        WorktreeOperationError.ARCHIVE_CONFLICT,
                        "Archived Workspace resources cannot be provisioned again");
            }
            Optional<RepositoryCommitId> existingBranch = findBranch(repository, requiredWorkspace);
            if (existingBranch.isPresent()) {
                throw failure(
                        WorktreeOperationError.BRANCH_CONFLICT,
                        "Managed Workspace branch already exists without its verified Worktree");
            }

            prepareRepositoryDirectory(requiredWorkspace.worktreeLocator());
            stageHook.reached(WorktreeProvisionStage.BEFORE_WORKTREE_ADD);
            try {
                gitCommands.addWorktree(
                        repository.canonicalPath(),
                        candidate,
                        requiredWorkspace.managedBranch(),
                        requiredWorkspace.baselineCommit());
                stageHook.reached(WorktreeProvisionStage.AFTER_WORKTREE_ADD);
                return verifyLocked(requiredWorkspace, policy, repository, candidate);
            } catch (RuntimeException operationFailure) {
                rollbackAfterProvisionFailure(requiredWorkspace, policy, repository, candidate);
                throw mapOperationFailure(operationFailure, WorktreeOperationError.COMMAND_FAILED);
            }
        }
    }

    /** Revalidates a retained Worktree under the shared non-blocking lifecycle lock. */
    public ManagedWorktree verify(ExecutionWorkspace workspace, WorkspacePolicy policy) {
        ExecutionWorkspace requiredWorkspace = requirePolicyLineage(workspace, policy);
        try (WorkspacePathLock ignored = lockManager.tryAcquire(requiredWorkspace.worktreeLocator())) {
            ManagedRepository repository = repositoryResolver.resolve(
                    requiredWorkspace.repositoryKey());
            Path candidate = resolveCandidate(requiredWorkspace.worktreeLocator());
            return verifyLocked(requiredWorkspace, policy, repository, candidate);
        }
    }

    /**
     * Removes an interrupted Provision only when path, repository, branch, HEAD and policy close.
     */
    public void rollbackProvisionOrphan(
            ExecutionWorkspace workspace, WorkspacePolicy policy) {
        ExecutionWorkspace requiredWorkspace = requirePolicyLineage(workspace, policy);
        try (WorkspacePathLock ignored = lockManager.tryAcquire(requiredWorkspace.worktreeLocator())) {
            ManagedRepository repository = repositoryResolver.resolve(
                    requiredWorkspace.repositoryKey());
            Path candidate = resolveCandidate(requiredWorkspace.worktreeLocator());
            boolean pathExists = existsNoFollow(candidate);
            Optional<RepositoryCommitId> branch = findBranch(repository, requiredWorkspace);
            if (!pathExists && branch.isEmpty()) {
                return;
            }
            if (!pathExists || branch.isEmpty()) {
                throw failure(
                        WorktreeOperationError.UNOWNED_PATH_RESIDUE,
                        "Provision residue does not close the complete Workspace identity");
            }
            verifyLocked(requiredWorkspace, policy, repository, candidate);
            removeVerifiedResources(requiredWorkspace, repository, candidate);
        }
    }

    /**
     * Publishes an immutable delivery Commit/Archive Ref without moving the active baseline branch,
     * then removes the exact Worktree and managed branch.
     */
    public WorktreeArchiveResult archive(
            ExecutionWorkspace workspace, WorkspacePolicy policy) {
        ExecutionWorkspace requiredWorkspace = requirePolicyLineage(workspace, policy);
        try (WorkspacePathLock ignored = lockManager.tryAcquire(requiredWorkspace.worktreeLocator())) {
            ManagedRepository repository = repositoryResolver.resolve(
                    requiredWorkspace.repositoryKey());
            Path candidate = resolveCandidate(requiredWorkspace.worktreeLocator());
            Optional<RepositoryCommitId> archived = findArchive(repository, requiredWorkspace);
            GitTreeId deliveryTree;
            RepositoryCommitId deliveryCommit;

            if (existsNoFollow(candidate)) {
                ManagedWorktree verified = verifyLocked(
                        requiredWorkspace, policy, repository, candidate);
                gitCommands.stageAll(verified.canonicalPath());
                deliveryTree = gitCommands.writeTree(verified.canonicalPath());
                if (archived.isPresent()) {
                    deliveryCommit = archived.orElseThrow();
                    requireValidArchive(
                            repository, requiredWorkspace, deliveryCommit, Optional.of(deliveryTree));
                } else {
                    deliveryCommit = createDelivery(
                            repository, requiredWorkspace, deliveryTree);
                }
                stageHook.reached(WorktreeProvisionStage.AFTER_ARCHIVE_REFERENCE);
                removeVerifiedResources(requiredWorkspace, repository, candidate);
            } else {
                deliveryCommit = archived.orElseThrow(() -> failure(
                        WorktreeOperationError.NOT_PROVISIONED,
                        "Managed Worktree and Archive Ref do not exist"));
                requireValidArchive(
                        repository, requiredWorkspace, deliveryCommit, Optional.empty());
                deliveryTree = gitCommands.commitTreeId(
                        repository.canonicalPath(), deliveryCommit);
                cleanupArchivedBranch(requiredWorkspace, repository);
            }
            return new WorktreeArchiveResult(
                    requiredWorkspace.id(),
                    requiredWorkspace.archiveReference(),
                    deliveryCommit,
                    deliveryTree);
        } catch (WorktreeOperationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw mapOperationFailure(failure, WorktreeOperationError.COMMAND_FAILED);
        }
    }

    private RepositoryCommitId createDelivery(
            ManagedRepository repository,
            ExecutionWorkspace workspace,
            GitTreeId deliveryTree) {
        try {
            RepositoryCommitId commit = gitCommands.commitTree(
                    repository.canonicalPath(),
                    deliveryTree,
                    workspace.baselineCommit(),
                    new GitCommitMessage("CrewScope delivery " + workspace.workspaceKey().value()));
            gitCommands.createArchiveReference(
                    repository.canonicalPath(), workspace.archiveReference(), commit);
            return commit;
        } catch (GitCommandException failure) {
            Optional<RepositoryCommitId> raced = findArchive(repository, workspace);
            if (raced.isPresent()) {
                RepositoryCommitId existing = raced.orElseThrow();
                requireValidArchive(repository, workspace, existing, Optional.of(deliveryTree));
                return existing;
            }
            throw mapOperationFailure(failure, WorktreeOperationError.COMMAND_FAILED);
        }
    }

    private void requireValidArchive(
            ManagedRepository repository,
            ExecutionWorkspace workspace,
            RepositoryCommitId deliveryCommit,
            Optional<GitTreeId> expectedTree) {
        try {
            if (!gitCommands.hasSingleParent(
                    repository.canonicalPath(), deliveryCommit, workspace.baselineCommit())) {
                throw archiveConflict();
            }
            GitTreeId actualTree = gitCommands.commitTreeId(
                    repository.canonicalPath(), deliveryCommit);
            if (expectedTree.isPresent() && !expectedTree.orElseThrow().equals(actualTree)) {
                throw archiveConflict();
            }
        } catch (WorktreeOperationException failure) {
            throw failure;
        } catch (GitCommandException failure) {
            throw archiveConflict();
        }
    }

    private ManagedWorktree verifyLocked(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            ManagedRepository repository,
            Path candidate) {
        Path canonicalWorktree = canonicalWorktree(candidate);
        requireOwner(canonicalWorktree);
        Path gitPointer = canonicalWorktree.resolve(".git");
        if (!existsNoFollow(gitPointer)) {
            throw failure(
                    WorktreeOperationError.UNOWNED_PATH_RESIDUE,
                    "Managed Worktree path is not an owned Git Worktree");
        }
        if (Files.isSymbolicLink(gitPointer)
                || !Files.isRegularFile(gitPointer, NO_FOLLOW_LINKS)) {
            throw failure(
                    WorktreeOperationError.CORRUPT_GIT_POINTER,
                    "Managed Worktree Git pointer is invalid");
        }

        Path commonDirectory;
        try {
            commonDirectory = gitCommands.commonDirectory(canonicalWorktree).toRealPath();
        } catch (IOException | GitCommandException failure) {
            throw new WorktreeOperationException(
                    WorktreeOperationError.CORRUPT_GIT_POINTER,
                    "Managed Worktree common Git directory could not be verified");
        }
        if (!commonDirectory.equals(repository.canonicalPath())) {
            throw failure(
                    WorktreeOperationError.CORRUPT_GIT_POINTER,
                    "Managed Worktree points to an unexpected Git repository");
        }

        RepositoryCommitId branchCommit = findBranch(repository, workspace)
                .orElseThrow(() -> failure(
                        WorktreeOperationError.CORRUPT_BRANCH,
                        "Managed Worktree branch is missing"));
        if (!branchCommit.equals(workspace.baselineCommit())) {
            throw failure(
                    WorktreeOperationError.CORRUPT_HEAD,
                    "Managed Worktree branch moved from its immutable baseline");
        }
        try {
            if (!gitCommands.isCurrentBranch(canonicalWorktree, workspace.managedBranch())) {
                throw failure(
                        WorktreeOperationError.CORRUPT_BRANCH,
                        "Managed Worktree is attached to an unexpected branch");
            }
        } catch (WorktreeOperationException failure) {
            throw failure;
        } catch (GitCommandException failure) {
            throw new WorktreeOperationException(
                    WorktreeOperationError.CORRUPT_BRANCH,
                    "Managed Worktree branch could not be verified");
        }

        RepositoryCommitId head;
        try {
            head = gitCommands.headCommit(canonicalWorktree);
        } catch (GitCommandException failure) {
            throw new WorktreeOperationException(
                    WorktreeOperationError.CORRUPT_HEAD,
                    "Managed Worktree HEAD could not be verified");
        }
        if (!head.equals(workspace.baselineCommit())) {
            throw failure(
                    WorktreeOperationError.CORRUPT_HEAD,
                    "Managed Worktree HEAD moved from its immutable baseline");
        }

        WorkspacePhysicalFingerprint physicalFingerprint = fingerprint(
                workspace,
                policy,
                repository.canonicalPath(),
                canonicalWorktree,
                commonDirectory,
                head);
        return new ManagedWorktree(
                workspace.id(),
                workspace.repositoryKey(),
                workspace.workspaceKey(),
                workspace.managedBranch(),
                workspace.baselineCommit(),
                head,
                physicalFingerprint,
                canonicalWorktree);
    }

    private void rollbackAfterProvisionFailure(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            ManagedRepository repository,
            Path candidate) {
        try {
            boolean pathExists = existsNoFollow(candidate);
            Optional<RepositoryCommitId> branch = findBranch(repository, workspace);
            if (!pathExists && branch.isEmpty()) {
                return;
            }
            if (!pathExists || branch.isEmpty()) {
                throw failure(
                        WorktreeOperationError.ROLLBACK_FAILED,
                        "Provision rollback found incomplete unowned residue");
            }
            verifyLocked(workspace, policy, repository, candidate);
            removeVerifiedResources(workspace, repository, candidate);
        } catch (RuntimeException rollbackFailure) {
            if (rollbackFailure instanceof WorktreeOperationException worktreeFailure
                    && worktreeFailure.error() == WorktreeOperationError.ROLLBACK_FAILED) {
                throw worktreeFailure;
            }
            throw new WorktreeOperationException(
                    WorktreeOperationError.ROLLBACK_FAILED,
                    "Managed Worktree provision rollback did not close");
        }
    }

    private void removeVerifiedResources(
            ExecutionWorkspace workspace,
            ManagedRepository repository,
            Path candidate) {
        try {
            stageHook.reached(WorktreeProvisionStage.BEFORE_WORKTREE_REMOVE);
            gitCommands.removeWorktree(repository.canonicalPath(), candidate);
            if (existsNoFollow(candidate)) {
                throw failure(
                        WorktreeOperationError.CLEANUP_FAILED,
                        "Managed Worktree path remained after cleanup");
            }
            cleanupArchivedBranch(workspace, repository);
        } catch (WorktreeOperationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new WorktreeOperationException(
                    WorktreeOperationError.CLEANUP_FAILED,
                    "Managed Worktree cleanup failed");
        }
    }

    private void cleanupArchivedBranch(
            ExecutionWorkspace workspace, ManagedRepository repository) {
        Optional<RepositoryCommitId> branch = findBranch(repository, workspace);
        if (branch.isEmpty()) {
            return;
        }
        if (!branch.orElseThrow().equals(workspace.baselineCommit())) {
            throw failure(
                    WorktreeOperationError.CORRUPT_HEAD,
                    "Managed branch moved before cleanup");
        }
        try {
            gitCommands.deleteManagedBranch(
                    repository.canonicalPath(),
                    workspace.managedBranch(),
                    workspace.baselineCommit());
        } catch (GitCommandException failure) {
            throw new WorktreeOperationException(
                    WorktreeOperationError.CLEANUP_FAILED,
                    "Managed branch cleanup failed");
        }
        if (findBranch(repository, workspace).isPresent()) {
            throw failure(
                    WorktreeOperationError.CLEANUP_FAILED,
                    "Managed branch remained after cleanup");
        }
    }

    private Optional<RepositoryCommitId> findBranch(
            ManagedRepository repository, ExecutionWorkspace workspace) {
        try {
            return gitCommands.findManagedBranch(
                    repository.canonicalPath(), workspace.managedBranch());
        } catch (GitCommandException failure) {
            throw mapOperationFailure(failure, WorktreeOperationError.COMMAND_FAILED);
        }
    }

    private Optional<RepositoryCommitId> findArchive(
            ManagedRepository repository, ExecutionWorkspace workspace) {
        try {
            return gitCommands.findArchiveReference(
                    repository.canonicalPath(), workspace.archiveReference());
        } catch (GitCommandException failure) {
            throw mapOperationFailure(failure, WorktreeOperationError.COMMAND_FAILED);
        }
    }

    private void prepareRepositoryDirectory(ManagedWorktreeLocator locator) {
        Path repositoryDirectory = canonicalWorktreeRoot.resolve(
                locator.repositoryKey().value());
        if (existsNoFollow(repositoryDirectory)) {
            if (Files.isSymbolicLink(repositoryDirectory)) {
                throw failure(
                        WorktreeOperationError.PATH_SYMLINK_ESCAPE,
                        "Managed Worktree parent must not be a symbolic link");
            }
            if (!Files.isDirectory(repositoryDirectory, NO_FOLLOW_LINKS)) {
                throw failure(
                        WorktreeOperationError.UNOWNED_PATH_RESIDUE,
                        "Managed Worktree parent contains an unowned filesystem entry");
            }
        } else {
            try {
                Files.createDirectory(repositoryDirectory);
            } catch (IOException failure) {
                throw new WorktreeOperationException(
                        WorktreeOperationError.COMMAND_FAILED,
                        "Managed Worktree parent could not be created");
            }
        }
        Path canonicalParent;
        try {
            canonicalParent = repositoryDirectory.toRealPath();
        } catch (IOException failure) {
            throw new WorktreeOperationException(
                    WorktreeOperationError.PATH_ESCAPE,
                    "Managed Worktree parent could not be resolved");
        }
        if (!canonicalParent.startsWith(canonicalWorktreeRoot)) {
            throw failure(
                    WorktreeOperationError.PATH_ESCAPE,
                    "Managed Worktree parent escaped its configured root");
        }
        requireOwner(canonicalParent);
    }

    private Path resolveCandidate(ManagedWorktreeLocator locator) {
        Path candidate = canonicalWorktreeRoot
                .resolve(locator.repositoryKey().value())
                .resolve(locator.workspaceKey().value())
                .normalize();
        if (!candidate.startsWith(canonicalWorktreeRoot)) {
            throw failure(
                    WorktreeOperationError.PATH_ESCAPE,
                    "Managed Worktree path escaped its configured root");
        }
        Path repositoryDirectory = candidate.getParent();
        if (Files.isSymbolicLink(repositoryDirectory) || Files.isSymbolicLink(candidate)) {
            throw failure(
                    WorktreeOperationError.PATH_SYMLINK_ESCAPE,
                    "Managed Worktree path must not contain symbolic links");
        }
        if (existsNoFollow(repositoryDirectory)) {
            if (!Files.isDirectory(repositoryDirectory, NO_FOLLOW_LINKS)) {
                throw failure(
                        WorktreeOperationError.UNOWNED_PATH_RESIDUE,
                        "Managed Worktree parent contains an unowned filesystem entry");
            }
            try {
                Path canonicalParent = repositoryDirectory.toRealPath();
                if (!canonicalParent.startsWith(canonicalWorktreeRoot)) {
                    throw failure(
                            WorktreeOperationError.PATH_ESCAPE,
                            "Managed Worktree parent escaped its configured root");
                }
                requireOwner(canonicalParent);
            } catch (IOException failure) {
                throw new WorktreeOperationException(
                        WorktreeOperationError.PATH_ESCAPE,
                        "Managed Worktree parent could not be resolved");
            }
        }
        return candidate;
    }

    private Path canonicalWorktree(Path candidate) {
        if (!existsNoFollow(candidate)) {
            throw failure(
                    WorktreeOperationError.NOT_PROVISIONED,
                    "Managed Worktree does not exist");
        }
        if (Files.isSymbolicLink(candidate)) {
            throw failure(
                    WorktreeOperationError.PATH_SYMLINK_ESCAPE,
                    "Managed Worktree path must not be a symbolic link");
        }
        if (!Files.isDirectory(candidate, NO_FOLLOW_LINKS)) {
            throw failure(
                    WorktreeOperationError.UNOWNED_PATH_RESIDUE,
                    "Managed Worktree path contains an unowned filesystem entry");
        }
        try {
            Path canonical = candidate.toRealPath();
            if (!canonical.startsWith(canonicalWorktreeRoot)) {
                throw failure(
                        WorktreeOperationError.PATH_ESCAPE,
                        "Managed Worktree escaped its configured root");
            }
            return canonical;
        } catch (IOException failure) {
            throw new WorktreeOperationException(
                    WorktreeOperationError.PATH_ESCAPE,
                    "Managed Worktree path could not be resolved");
        }
    }

    private ExecutionWorkspace requirePolicyLineage(
            ExecutionWorkspace workspace, WorkspacePolicy policy) {
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        WorkspacePolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        boolean matches = requiredWorkspace.scope().equals(requiredPolicy.scope())
                && requiredWorkspace.taskId().equals(requiredPolicy.taskId())
                && requiredWorkspace.taskExecutionId().equals(requiredPolicy.taskExecutionId())
                && requiredWorkspace.attempt() == requiredPolicy.attempt()
                && requiredWorkspace.codingTarget().equals(requiredPolicy.codingTarget());
        if (!matches) {
            throw failure(
                    WorktreeOperationError.POLICY_MISMATCH,
                    "Workspace Policy does not match the complete Workspace lineage");
        }
        return requiredWorkspace;
    }

    private WorkspacePhysicalFingerprint fingerprint(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            Path repository,
            Path worktree,
            Path commonDirectory,
            RepositoryCommitId head) {
        StringBuilder canonical = new StringBuilder("workspace-physical-v1");
        append(canonical, workspace.id().toString());
        append(canonical, workspace.fingerprint().value());
        append(canonical, workspace.repositoryBindingId().toString());
        append(canonical, Long.toString(workspace.repositoryBindingVersion()));
        append(canonical, workspace.repositoryKey().value());
        append(canonical, repository.toString());
        append(canonical, worktree.toString());
        append(canonical, workspace.taskExecutionId().toString());
        append(canonical, Integer.toString(workspace.attempt()));
        append(canonical, workspace.managedBranch().value());
        append(canonical, workspace.baselineCommit().value());
        append(canonical, head.value());
        append(canonical, commonDirectory.toString());
        append(canonical, workspace.ownership().environment().value());
        append(canonical, workspace.ownership().runtimeId().toString());
        append(canonical, workspace.ownership().workerId().toString());
        append(canonical, workspace.ownership().leaseId().toString());
        append(canonical, workspace.ownership().fencingToken().toString());
        append(canonical, policy.id().toString());
        append(canonical, policy.policyHash().toString());
        policy.allowedPaths().values().forEach(value -> append(canonical, value));
        BuildProfileReference profile = policy.buildProfile();
        append(canonical, profile.key());
        append(canonical, Long.toString(profile.version()));
        append(canonical, profile.profileHash().toString());
        append(canonical, policy.sandboxBudget().networkMode().name());
        append(canonical, Integer.toString(policy.sandboxBudget().cpuCount()));
        append(canonical, Integer.toString(policy.sandboxBudget().memoryMiB()));
        append(canonical, Integer.toString(policy.sandboxBudget().pids()));
        append(canonical, Integer.toString(policy.sandboxBudget().maxCommandDurationSeconds()));
        append(canonical, Long.toString(policy.sandboxBudget().maxCommandOutputBytes()));
        append(canonical, Boolean.toString(policy.sandboxBudget().readOnlyRootFilesystem()));
        append(canonical, Integer.toString(policy.operationBudget().maxCommandCalls()));
        append(canonical, Integer.toString(policy.operationBudget().maxChangedFiles()));
        append(canonical, Long.toString(policy.operationBudget().maxSingleFileBytes()));
        append(canonical, Integer.toString(policy.operationBudget().maxWriteOperations()));
        append(canonical, Long.toString(policy.operationBudget().maxWrittenBytes()));
        append(canonical, Long.toString(policy.operationBudget().maxDiffBytes()));
        append(canonical, Integer.toString(policy.operationBudget().maxTestRepairRounds()));
        return new WorkspacePhysicalFingerprint(sha256(canonical.toString()));
    }

    private void requireOwner(Path path) {
        try {
            String owner = Files.getOwner(path, NO_FOLLOW_LINKS).getName();
            if (!requiredOwner.equals(owner)) {
                throw failure(
                        WorktreeOperationError.WORKSPACE_MISMATCH,
                        "Managed Worktree owner does not match the Worker owner");
            }
        } catch (IOException failure) {
            throw new WorktreeOperationException(
                    WorktreeOperationError.WORKSPACE_MISMATCH,
                    "Managed Worktree owner could not be verified");
        }
    }

    private static Path canonicalRoot(Path configuredRoot) {
        Path root = Objects.requireNonNull(configuredRoot, "worktreeRoot")
                .toAbsolutePath()
                .normalize();
        try {
            if (Files.isSymbolicLink(root)) {
                throw invalidRoot();
            }
            Path canonical = root.toRealPath();
            if (!Files.isDirectory(canonical, NO_FOLLOW_LINKS)) {
                throw invalidRoot();
            }
            return canonical;
        } catch (IOException failure) {
            throw invalidRoot();
        }
    }

    private static boolean existsNoFollow(Path path) {
        return Files.exists(path, NO_FOLLOW_LINKS);
    }

    private static String requireOwner(String owner) {
        String value = Objects.requireNonNull(owner, "requiredOwner").trim();
        if (value.isEmpty() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Managed Worktree owner must be non-blank");
        }
        return value;
    }

    private static void append(StringBuilder target, String value) {
        String required = Objects.requireNonNull(value, "fingerprintValue");
        target.append('|').append(required.length()).append(':').append(required);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available", impossible);
        }
    }

    private static WorktreeOperationException mapOperationFailure(
            RuntimeException failure, WorktreeOperationError fallback) {
        if (failure instanceof WorktreeOperationException worktreeFailure) {
            return worktreeFailure;
        }
        if (failure instanceof GitCommandException gitFailure) {
            WorktreeOperationError error = gitFailure.error() == GitCommandError.CONFLICT
                    ? WorktreeOperationError.BRANCH_CONFLICT
                    : fallback;
            return new WorktreeOperationException(error, safeSummary(error));
        }
        return new WorktreeOperationException(fallback, safeSummary(fallback));
    }

    private static String safeSummary(WorktreeOperationError error) {
        return switch (error) {
            case BRANCH_CONFLICT -> "Managed Workspace branch conflicts with an existing value";
            case CLEANUP_FAILED -> "Managed Worktree cleanup failed";
            case COMMAND_FAILED -> "Managed Worktree command failed";
            default -> "Managed Worktree lifecycle operation failed";
        };
    }

    private static WorktreeOperationException archiveConflict() {
        return failure(
                WorktreeOperationError.ARCHIVE_CONFLICT,
                "Archive Ref does not match the expected delivery parent and tree");
    }

    private static WorktreeOperationException invalidRoot() {
        return failure(
                WorktreeOperationError.MANAGED_ROOT_INVALID,
                "Managed Worktree root must be a canonical directory");
    }

    private static WorktreeOperationException failure(
            WorktreeOperationError error, String summary) {
        return new WorktreeOperationException(error, summary);
    }
}
