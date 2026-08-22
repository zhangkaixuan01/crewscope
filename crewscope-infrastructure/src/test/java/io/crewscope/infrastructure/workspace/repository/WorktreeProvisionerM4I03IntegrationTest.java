package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceFingerprint;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.ExecutionWorkspaceOwnership;
import io.crewscope.domain.coding.ManagedWorkspaceBranch;
import io.crewscope.domain.coding.ManagedWorktreeLocator;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.SandboxResourceBudget;
import io.crewscope.domain.coding.SandboxNetworkMode;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.git.GitCommandPolicy;
import io.crewscope.infrastructure.workspace.git.GitCommitMessage;
import io.crewscope.infrastructure.workspace.git.GitTreeId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Git/filesystem fault matrix for the M4-I03 managed Worktree lifecycle. */
class WorktreeProvisionerM4I03IntegrationTest {

    private static final Duration HOST_COMMAND_TIMEOUT = Duration.ofSeconds(15);
    private static final RepositoryKey REPOSITORY_KEY = new RepositoryKey("repository-01");

    @TempDir Path temporaryDirectory;

    private Path managedRoot;
    private Path worktreeRoot;
    private Path lockRoot;
    private Path bareRepository;
    private GitCommandExecutor gitCommands;
    private ManagedRepositoryResolver repositoryResolver;
    private WorkspacePathLockManager lockManager;
    private WorktreeProvisioner provisioner;
    private RepositoryCommitId baseline;
    private ExecutionWorkspace workspace;
    private WorkspacePolicy policy;
    private Path expectedWorktree;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(commandSucceeds("git", "--version"), "Git is required");
        managedRoot = Files.createDirectory(temporaryDirectory.resolve("repositories"));
        worktreeRoot = Files.createDirectory(temporaryDirectory.resolve("worktrees"));
        lockRoot = Files.createDirectory(temporaryDirectory.resolve("locks"));
        Path source = temporaryDirectory.resolve("source");
        bareRepository = managedRoot.resolve(REPOSITORY_KEY.value() + ".git");

        runRequired(temporaryDirectory, "git", "init", "--initial-branch=main", source.toString());
        runRequired(source, "git", "config", "user.name", "Fixture");
        runRequired(source, "git", "config", "user.email", "fixture@crewscope.local");
        Files.writeString(source.resolve("README.md"), "baseline\n", StandardCharsets.UTF_8);
        runRequired(source, "git", "add", "README.md");
        runRequired(source, "git", "commit", "-m", "initial fixture");
        runRequired(
                temporaryDirectory,
                "git",
                "clone",
                "--bare",
                source.toString(),
                bareRepository.toString());

        gitCommands = new GitCommandExecutor(new GitCommandPolicy(
                temporaryDirectory.resolve("git-home"), Duration.ofSeconds(10), 1024 * 1024));
        baseline = gitCommands.resolveBranch(
                bareRepository, new RepositoryBranchName("main"));
        String owner = Files.getOwner(worktreeRoot).getName();
        repositoryResolver = new ManagedRepositoryResolver(managedRoot, owner, gitCommands);
        lockManager = new WorkspacePathLockManager(lockRoot);
        provisioner = new WorktreeProvisioner(
                worktreeRoot, owner, repositoryResolver, gitCommands, lockManager);
        WorkspaceFacts facts = workspaceFacts();
        workspace = facts.workspace();
        policy = facts.policy();
        expectedWorktree = worktreeRoot.resolve(workspace.worktreeLocator().relativeValue());
    }

    @Test
    void provisionsIdempotentlyAndReturnsAPathFreePhysicalFingerprint() {
        ManagedWorktree created = provisioner.provision(workspace, policy);
        ManagedWorktree recovered = provisioner.provision(workspace, policy);

        assertTrue(Files.isDirectory(expectedWorktree));
        assertEquals(baseline, created.headCommit());
        assertEquals(created.physicalFingerprint(), recovered.physicalFingerprint());
        assertEquals(
                baseline,
                gitCommands.findManagedBranch(bareRepository, workspace.managedBranch())
                        .orElseThrow());
        assertFalse(created.toString().contains(worktreeRoot.toString()));
        assertTrue(Arrays.stream(ManagedWorktree.class.getMethods())
                .filter(method -> method.getDeclaringClass() == ManagedWorktree.class)
                .noneMatch(method -> method.getReturnType() == Path.class
                        || Arrays.asList(method.getParameterTypes()).contains(Path.class)));
    }

    @Test
    void sharedJvmAndOsLockRejectACompetingProvisionerWithoutWaiting() {
        WorkspacePathLock held = lockManager.tryAcquire(workspace.worktreeLocator());
        try {
            WorktreeProvisioner competitor = new WorktreeProvisioner(
                    worktreeRoot,
                    requiredOwner(),
                    repositoryResolver,
                    gitCommands,
                    new WorkspacePathLockManager(lockRoot));

            WorktreeOperationException failure = assertThrows(
                    WorktreeOperationException.class,
                    () -> competitor.provision(workspace, policy));

            assertEquals(WorktreeOperationError.WORKSPACE_BUSY, failure.error());
            assertFalse(Files.exists(expectedWorktree));
        } finally {
            held.close();
        }
    }

    @Test
    void ordinaryFailureAfterGitAddRollsBackPathAndBranchCompletely() {
        WorktreeProvisioner failing = provisionerWithHook(stage -> {
            if (stage == WorktreeProvisionStage.AFTER_WORKTREE_ADD) {
                throw new IllegalStateException("fixture failure");
            }
        });

        WorktreeOperationException failure = assertThrows(
                WorktreeOperationException.class, () -> failing.provision(workspace, policy));

        assertEquals(WorktreeOperationError.COMMAND_FAILED, failure.error());
        assertFalse(Files.exists(expectedWorktree));
        assertTrue(gitCommands.findManagedBranch(bareRepository, workspace.managedBranch()).isEmpty());
    }

    @Test
    void coldRecoveryCleansAnExactProcessExitOrphan() {
        WorktreeProvisioner crashing = provisionerWithHook(stage -> {
            if (stage == WorktreeProvisionStage.AFTER_WORKTREE_ADD) {
                throw new SimulatedProcessExit();
            }
        });

        assertThrows(SimulatedProcessExit.class, () -> crashing.provision(workspace, policy));
        assertTrue(Files.isDirectory(expectedWorktree));

        WorktreeProvisioner recovered = new WorktreeProvisioner(
                worktreeRoot,
                requiredOwner(),
                repositoryResolver,
                gitCommands,
                new WorkspacePathLockManager(lockRoot));
        recovered.rollbackProvisionOrphan(workspace, policy);

        assertFalse(Files.exists(expectedWorktree));
        assertTrue(gitCommands.findManagedBranch(bareRepository, workspace.managedBranch()).isEmpty());
    }

    @Test
    void preservesPreexistingDirectoryResidueAndFailsClosed() throws Exception {
        Files.createDirectories(expectedWorktree);

        WorktreeOperationException failure = assertThrows(
                WorktreeOperationException.class,
                () -> provisioner.provision(workspace, policy));

        assertEquals(WorktreeOperationError.UNOWNED_PATH_RESIDUE, failure.error());
        assertTrue(Files.isDirectory(expectedWorktree));
    }

    @Test
    void rejectsAConflictingManagedBranchWithoutDeletingIt() {
        gitCommands.addWorktree(
                bareRepository,
                temporaryDirectory.resolve("other-worktree"),
                workspace.managedBranch(),
                baseline);

        WorktreeOperationException failure = assertThrows(
                WorktreeOperationException.class,
                () -> provisioner.provision(workspace, policy));

        assertEquals(WorktreeOperationError.BRANCH_CONFLICT, failure.error());
        assertTrue(gitCommands.findManagedBranch(bareRepository, workspace.managedBranch()).isPresent());
    }

    @Test
    void detectsMovedHeadDetachedBranchAndInvalidGitPointerWithoutCleaningEvidence()
            throws Exception {
        provisioner.provision(workspace, policy);
        Files.writeString(expectedWorktree.resolve("README.md"), "moved\n", StandardCharsets.UTF_8);
        runRequired(expectedWorktree, "git", "add", "README.md");
        runRequired(
                expectedWorktree,
                "git",
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@crewscope.local",
                "commit",
                "-m",
                "move head");

        assertError(WorktreeOperationError.CORRUPT_HEAD, () -> provisioner.verify(workspace, policy));
        assertTrue(Files.isDirectory(expectedWorktree));

        runRequired(expectedWorktree, "git", "reset", "--hard", baseline.value());
        runRequired(expectedWorktree, "git", "checkout", "--detach", baseline.value());
        assertError(
                WorktreeOperationError.CORRUPT_BRANCH,
                () -> provisioner.verify(workspace, policy));

        runRequired(expectedWorktree, "git", "checkout", workspace.managedBranch().value());
        Files.writeString(
                expectedWorktree.resolve(".git"),
                "gitdir: " + temporaryDirectory.resolve("outside-git") + "\n",
                StandardCharsets.UTF_8);
        assertError(
                WorktreeOperationError.CORRUPT_GIT_POINTER,
                () -> provisioner.verify(workspace, policy));
        assertTrue(Files.isDirectory(expectedWorktree));
    }

    @Test
    void rejectsWorktreeSymlinkEscapeWithoutFollowingOrDeletingTarget() throws Exception {
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Files.createDirectory(worktreeRoot.resolve(REPOSITORY_KEY.value()));
        Files.createSymbolicLink(expectedWorktree, outside);

        assertError(
                WorktreeOperationError.PATH_SYMLINK_ESCAPE,
                () -> provisioner.provision(workspace, policy));
        assertTrue(Files.isSymbolicLink(expectedWorktree));
        assertTrue(Files.isDirectory(outside));
    }

    @Test
    void rejectsPolicyFromAnotherExecutionBeforeTouchingFilesystem() {
        WorkspaceFacts other = workspaceFacts();

        assertError(
                WorktreeOperationError.POLICY_MISMATCH,
                () -> provisioner.provision(workspace, other.policy()));
        assertFalse(Files.exists(expectedWorktree));
    }

    @Test
    void archivesChangedTreeWithoutMovingBaselineAndResumesIdempotently() throws Exception {
        provisioner.provision(workspace, policy);
        Files.writeString(expectedWorktree.resolve("README.md"), "delivery\n", StandardCharsets.UTF_8);
        Files.writeString(expectedWorktree.resolve("New.java"), "final class New {}\n", StandardCharsets.UTF_8);

        WorktreeArchiveResult archived = provisioner.archive(workspace, policy);
        WorktreeArchiveResult retried = provisioner.archive(workspace, policy);

        assertEquals(archived, retried);
        assertTrue(gitCommands.hasSingleParent(
                bareRepository, archived.deliveryCommit(), baseline));
        assertEquals(
                archived.deliveryTree(),
                gitCommands.commitTreeId(bareRepository, archived.deliveryCommit()));
        assertEquals(
                "delivery\n",
                gitCommands.show(
                        bareRepository,
                        archived.deliveryCommit(),
                        new io.crewscope.domain.coding.DiffPath("README.md")));
        assertFalse(Files.exists(expectedWorktree));
        assertTrue(gitCommands.findManagedBranch(bareRepository, workspace.managedBranch()).isEmpty());
        assertEquals(
                archived.deliveryCommit(),
                gitCommands.findArchiveReference(bareRepository, workspace.archiveReference())
                        .orElseThrow());
    }

    @Test
    void archiveReferencePublishedBeforeExitIsUsedToResumeCleanup() throws Exception {
        provisioner.provision(workspace, policy);
        Files.writeString(expectedWorktree.resolve("README.md"), "delivery\n", StandardCharsets.UTF_8);
        WorktreeProvisioner crashing = provisionerWithHook(stage -> {
            if (stage == WorktreeProvisionStage.AFTER_ARCHIVE_REFERENCE) {
                throw new SimulatedProcessExit();
            }
        });

        assertThrows(SimulatedProcessExit.class, () -> crashing.archive(workspace, policy));
        RepositoryCommitId published = gitCommands
                .findArchiveReference(bareRepository, workspace.archiveReference())
                .orElseThrow();
        assertTrue(Files.isDirectory(expectedWorktree));

        WorktreeArchiveResult resumed = provisioner.archive(workspace, policy);

        assertEquals(published, resumed.deliveryCommit());
        assertFalse(Files.exists(expectedWorktree));
    }

    @Test
    void conflictingArchiveReferencePreservesWorktreeAndBranch() throws Exception {
        provisioner.provision(workspace, policy);
        GitTreeId baselineTree = gitCommands.commitTreeId(bareRepository, baseline);
        RepositoryCommitId conflicting = gitCommands.commitTree(
                bareRepository,
                baselineTree,
                baseline,
                new GitCommitMessage("conflicting archive"));
        gitCommands.createArchiveReference(
                bareRepository, workspace.archiveReference(), conflicting);
        Files.writeString(expectedWorktree.resolve("README.md"), "different\n", StandardCharsets.UTF_8);

        assertError(
                WorktreeOperationError.ARCHIVE_CONFLICT,
                () -> provisioner.archive(workspace, policy));
        assertTrue(Files.isDirectory(expectedWorktree));
        assertEquals(
                baseline,
                gitCommands.findManagedBranch(bareRepository, workspace.managedBranch())
                        .orElseThrow());
    }

    @Test
    void cleanupFailurePreservesPublishedArchiveAndCanBeRetried() throws Exception {
        provisioner.provision(workspace, policy);
        Files.writeString(expectedWorktree.resolve("README.md"), "delivery\n", StandardCharsets.UTF_8);
        WorktreeProvisioner failing = provisionerWithHook(stage -> {
            if (stage == WorktreeProvisionStage.BEFORE_WORKTREE_REMOVE) {
                throw new IllegalStateException("fixture cleanup failure");
            }
        });

        assertError(
                WorktreeOperationError.CLEANUP_FAILED,
                () -> failing.archive(workspace, policy));
        assertTrue(Files.isDirectory(expectedWorktree));
        assertTrue(gitCommands
                .findArchiveReference(bareRepository, workspace.archiveReference())
                .isPresent());

        WorktreeArchiveResult resumed = provisioner.archive(workspace, policy);
        assertFalse(Files.exists(expectedWorktree));
        assertEquals(
                resumed.deliveryCommit(),
                gitCommands.findArchiveReference(bareRepository, workspace.archiveReference())
                        .orElseThrow());
    }

    @Test
    void finalizingRecoveryRestoresTheExactArchivedTreeAndReusesTheDeliveryCommit()
            throws Exception {
        provisioner.provision(workspace, policy);
        Files.writeString(expectedWorktree.resolve("README.md"), "delivery\n", StandardCharsets.UTF_8);
        Files.write(expectedWorktree.resolve("payload.bin"), new byte[] {0, 1, 2, 3, -1});
        WorktreeArchiveResult first = provisioner.archive(workspace, policy);

        ManagedWorktree restored = provisioner.recoverFinalizing(workspace, policy);

        assertEquals(baseline, restored.headCommit());
        assertEquals("delivery\n", Files.readString(expectedWorktree.resolve("README.md")));
        assertTrue(Arrays.equals(
                new byte[] {0, 1, 2, 3, -1},
                Files.readAllBytes(expectedWorktree.resolve("payload.bin"))));
        assertEquals(first.deliveryTree(), gitCommands.writeTree(expectedWorktree));

        WorktreeArchiveResult retried = provisioner.archive(workspace, policy);
        assertEquals(first, retried);
        assertFalse(Files.exists(expectedWorktree));
    }

    @Test
    void finalizingRecoveryAcceptsAnEmptyDeliveryPatch() {
        provisioner.provision(workspace, policy);
        WorktreeArchiveResult archived = provisioner.archive(workspace, policy);

        ManagedWorktree restored = provisioner.recoverFinalizing(workspace, policy);

        assertEquals(baseline, restored.headCommit());
        assertEquals(archived.deliveryTree(), gitCommands.writeTree(expectedWorktree));
        assertEquals(archived, provisioner.archive(workspace, policy));
    }

    @Test
    void ordinaryFinalizingRestoreFailureRollsBackAndReleasesTheLifecycleLock()
            throws Exception {
        provisioner.provision(workspace, policy);
        Files.writeString(expectedWorktree.resolve("README.md"), "delivery\n", StandardCharsets.UTF_8);
        WorktreeArchiveResult archived = provisioner.archive(workspace, policy);
        WorktreeProvisioner failing = provisionerWithHook(stage -> {
            if (stage == WorktreeProvisionStage.AFTER_ARCHIVE_RESTORE) {
                throw new IllegalStateException("fixture restore failure");
            }
        });

        assertError(
                WorktreeOperationError.COMMAND_FAILED,
                () -> failing.recoverFinalizing(workspace, policy));
        assertFalse(Files.exists(expectedWorktree));
        assertTrue(gitCommands.findManagedBranch(bareRepository, workspace.managedBranch()).isEmpty());

        ManagedWorktree retried = provisioner.recoverFinalizing(workspace, policy);
        assertEquals(archived.deliveryTree(), gitCommands.writeTree(retried.canonicalPath()));
    }

    @Test
    void coldRecoveryAcceptsAProcessExitAfterArchivedTreeRestoreWithoutDuplicateDelivery()
            throws Exception {
        provisioner.provision(workspace, policy);
        Files.writeString(expectedWorktree.resolve("README.md"), "delivery\n", StandardCharsets.UTF_8);
        WorktreeArchiveResult archived = provisioner.archive(workspace, policy);
        WorktreeProvisioner crashing = provisionerWithHook(stage -> {
            if (stage == WorktreeProvisionStage.AFTER_ARCHIVE_RESTORE) {
                throw new SimulatedProcessExit();
            }
        });

        assertThrows(
                SimulatedProcessExit.class,
                () -> crashing.recoverFinalizing(workspace, policy));
        assertTrue(Files.isDirectory(expectedWorktree));

        WorktreeProvisioner cold = new WorktreeProvisioner(
                worktreeRoot,
                requiredOwner(),
                repositoryResolver,
                gitCommands,
                new WorkspacePathLockManager(lockRoot));
        cold.recoverFinalizing(workspace, policy);
        WorktreeArchiveResult retried = cold.archive(workspace, policy);

        assertEquals(archived, retried);
        assertFalse(Files.exists(expectedWorktree));
    }

    private WorktreeProvisioner provisionerWithHook(WorktreeStageHook hook) {
        return new WorktreeProvisioner(
                worktreeRoot,
                requiredOwner(),
                repositoryResolver,
                gitCommands,
                lockManager,
                hook);
    }

    private WorkspaceFacts workspaceFacts() {
        WorkItemScope scope = mock(WorkItemScope.class);
        TaskId taskId = TaskId.generate();
        TaskExecutionId executionId = TaskExecutionId.generate();
        ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        ExecutionWorkspaceKey workspaceKey = ExecutionWorkspaceKey.derive(workspaceId, 1);
        CodingTargetSnapshotReference target = new CodingTargetSnapshotReference(
                CodingTargetSnapshotId.generate(), 1, TaskFactHash.sha256("target"));

        ExecutionWorkspace candidate = mock(ExecutionWorkspace.class);
        when(candidate.id()).thenReturn(workspaceId);
        when(candidate.scope()).thenReturn(scope);
        when(candidate.taskId()).thenReturn(taskId);
        when(candidate.taskExecutionId()).thenReturn(executionId);
        when(candidate.attempt()).thenReturn(1);
        when(candidate.codingTarget()).thenReturn(target);
        when(candidate.repositoryBindingId()).thenReturn(RepositoryBindingId.generate());
        when(candidate.repositoryBindingVersion()).thenReturn(1L);
        when(candidate.repositoryKey()).thenReturn(REPOSITORY_KEY);
        when(candidate.baselineCommit()).thenReturn(baseline);
        when(candidate.workspaceKey()).thenReturn(workspaceKey);
        when(candidate.managedBranch()).thenReturn(ManagedWorkspaceBranch.derive(executionId, 1));
        when(candidate.archiveReference())
                .thenReturn(io.crewscope.domain.coding.WorkspaceArchiveReference.derive(workspaceKey));
        when(candidate.worktreeLocator())
                .thenReturn(new ManagedWorktreeLocator(REPOSITORY_KEY, workspaceKey));
        when(candidate.ownership()).thenReturn(new ExecutionWorkspaceOwnership(
                new RuntimeEnvironment("test"),
                ExecutionRuntimeId.generate(),
                RuntimeWorkerId.generate(),
                ExecutionLeaseId.generate(),
                FencingToken.initial()));
        when(candidate.fingerprint())
                .thenReturn(new ExecutionWorkspaceFingerprint(TaskFactHash.sha256("workspace").value()));

        WorkspacePolicy workspacePolicy = mock(WorkspacePolicy.class);
        when(workspacePolicy.id()).thenReturn(WorkspacePolicyId.generate());
        when(workspacePolicy.scope()).thenReturn(scope);
        when(workspacePolicy.taskId()).thenReturn(taskId);
        when(workspacePolicy.taskExecutionId()).thenReturn(executionId);
        when(workspacePolicy.attempt()).thenReturn(1);
        when(workspacePolicy.codingTarget()).thenReturn(target);
        when(workspacePolicy.policyHash()).thenReturn(TaskFactHash.sha256("policy"));
        when(workspacePolicy.allowedPaths()).thenReturn(AllowedPathSet.of("src"));
        when(workspacePolicy.buildProfile()).thenReturn(new BuildProfileReference(
                "java-maven", 1, TaskFactHash.sha256("profile")));
        when(workspacePolicy.sandboxBudget()).thenReturn(new SandboxResourceBudget(
                SandboxNetworkMode.NONE, 2, 512, 64, 60, 1024 * 1024, true));
        when(workspacePolicy.operationBudget()).thenReturn(new WorkspaceOperationBudget(
                10, 20, 1024 * 1024, 50, 10 * 1024 * 1024, 10 * 1024 * 1024, 2));
        return new WorkspaceFacts(candidate, workspacePolicy);
    }

    private String requiredOwner() {
        try {
            return Files.getOwner(worktreeRoot).getName();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void assertError(WorktreeOperationError expected, Runnable operation) {
        WorktreeOperationException failure = assertThrows(
                WorktreeOperationException.class, operation::run);
        assertEquals(expected, failure.error());
    }

    private static boolean commandSucceeds(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(HOST_COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    && process.exitValue() == 0;
        } catch (IOException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String runRequired(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(HOST_COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Fixture command timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Fixture command failed: " + output);
        }
        return output;
    }

    private record WorkspaceFacts(ExecutionWorkspace workspace, WorkspacePolicy policy) {}

    private static final class SimulatedProcessExit extends Error {}
}
