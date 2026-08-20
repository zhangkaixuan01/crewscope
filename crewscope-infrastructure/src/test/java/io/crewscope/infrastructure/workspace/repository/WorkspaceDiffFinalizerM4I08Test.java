package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.DiffArtifactRepository;
import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceFingerprint;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.WorkspaceArchiveReference;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.artifact.FilesystemArtifactStore;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.git.GitCommandPolicy;
import io.crewscope.infrastructure.workspace.git.GitTreeId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Real commit-pair and ArtifactStore proof for idempotent M4-I08 final publication. */
class WorkspaceDiffFinalizerM4I08Test {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    private Path repositoryPath;
    private RepositoryCommitId baseline;
    private RepositoryCommitId delivery;
    private GitTreeId deliveryTree;
    private WorkspaceArchiveReference archiveReference;
    private Fixture fixture;
    private InMemoryDiffRepository diffRepository;
    private WorkspaceDiffFinalizer finalizer;

    @BeforeEach
    void setUp() throws Exception {
        repositoryPath = Files.createDirectories(temporaryDirectory.resolve("repository"));
        run("git", "init", "--initial-branch=main", repositoryPath.toString());
        run("git", "-C", repositoryPath.toString(), "config", "user.name", "CrewScope Finalizer");
        run("git", "-C", repositoryPath.toString(), "config", "user.email", "finalizer@crewscope.local");
        Files.createDirectories(repositoryPath.resolve("src"));
        Files.writeString(repositoryPath.resolve("src/App.java"), "class App {}\n");
        run("git", "-C", repositoryPath.toString(), "add", "--all");
        run("git", "-C", repositoryPath.toString(), "commit", "-m", "baseline");
        baseline = commit("HEAD");
        Files.writeString(repositoryPath.resolve("src/App.java"), "class App { int value; }\n");
        Files.writeString(repositoryPath.resolve("src/New.java"), "class New {}\n");
        run("git", "-C", repositoryPath.toString(), "add", "--all");
        run("git", "-C", repositoryPath.toString(), "commit", "-m", "delivery");
        delivery = commit("HEAD");

        fixture = Fixture.create(baseline);
        archiveReference = fixture.archiveReference;
        run(
                "git",
                "-C",
                repositoryPath.toString(),
                "update-ref",
                archiveReference.value(),
                delivery.value());
        GitCommandExecutor git = new GitCommandExecutor(new GitCommandPolicy(
                temporaryDirectory.resolve("git-home"), Duration.ofSeconds(15), 4 * 1024 * 1024));
        deliveryTree = git.commitTreeId(repositoryPath, delivery);
        ManagedRepositoryResolver repositories = mock(ManagedRepositoryResolver.class);
        when(repositories.resolve(fixture.repositoryKey))
                .thenReturn(new ManagedRepository(fixture.repositoryKey, repositoryPath.toRealPath()));
        ExecutionWorkspaceRepository workspaces = mock(ExecutionWorkspaceRepository.class);
        when(workspaces.findById(
                        fixture.scope.organizationId(),
                        fixture.scope.teamId(),
                        fixture.scope.projectId(),
                        fixture.workspaceId))
                .thenReturn(Optional.of(fixture.workspace));
        WorkspaceDiffProperties properties = new WorkspaceDiffProperties();
        GitWorkspaceDiffReconciler reconciler = new GitWorkspaceDiffReconciler(git, properties);
        PatchArtifactWriter patches = new PatchArtifactWriter(new FilesystemArtifactStore(
                temporaryDirectory.resolve("artifacts"), new ObjectMapper(), CLOCK));
        diffRepository = new InMemoryDiffRepository();
        io.crewscope.application.transaction.TransactionExecutor transactions =
                mock(io.crewscope.application.transaction.TransactionExecutor.class);
        when(transactions.required(any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        finalizer = new WorkspaceDiffFinalizer(
                repositories,
                git,
                reconciler,
                patches,
                diffRepository,
                workspaces,
                CLOCK,
                io.crewscope.application.coding.CodingTaskTimelinePublisher.NO_OP,
                transactions);
    }

    @Test
    void publishesCommitAuthorityAndReturnsSameArtifactOnRetry() {
        WorktreeArchiveResult archived = new WorktreeArchiveResult(
                fixture.workspaceId, archiveReference, delivery, deliveryTree);
        DiffArtifact first = finalizer.finalizeDiff(
                fixture.workspace,
                fixture.target,
                fixture.policy,
                archived,
                fixture.actor,
                Optional.empty());
        DiffArtifact retried = finalizer.finalizeDiff(
                fixture.workspace,
                fixture.target,
                fixture.policy,
                archived,
                fixture.actor,
                Optional.empty());

        assertSame(first, retried);
        assertEquals(2, first.manifest().fileCount());
        assertEquals(baseline, first.baselineCommit());
        assertEquals(delivery, first.deliveryCommit());
        assertTrue(first.patchArtifact().sizeBytes() > 0);
        assertEquals(1, diffRepository.creates.get());
    }

    @Test
    void rejectsArchiveCommitWhoseParentIsNotTheWorkspaceBaseline() throws Exception {
        run(
                "git",
                "-C",
                repositoryPath.toString(),
                "update-ref",
                archiveReference.value(),
                baseline.value(),
                delivery.value());
        WorktreeArchiveResult invalid = new WorktreeArchiveResult(
                fixture.workspaceId,
                archiveReference,
                baseline,
                new GitCommandExecutor(new GitCommandPolicy(
                                temporaryDirectory.resolve("other-git-home"),
                                Duration.ofSeconds(15),
                                4 * 1024 * 1024))
                        .commitTreeId(repositoryPath, baseline));

        WorkspaceDiffException failure = assertThrows(
                WorkspaceDiffException.class,
                () -> finalizer.finalizeDiff(
                        fixture.workspace,
                        fixture.target,
                        fixture.policy,
                        invalid,
                        fixture.actor,
                        Optional.empty()));
        assertEquals(WorkspaceDiffError.FINALIZATION_CONFLICT, failure.error());
        assertEquals(0, diffRepository.creates.get());
    }

    private RepositoryCommitId commit(String revision) throws Exception {
        return new RepositoryCommitId(run(
                        "git", "-C", repositoryPath.toString(), "rev-parse", revision)
                .strip());
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("Git finalizer fixture command failed");
        }
        return output;
    }

    private static final class InMemoryDiffRepository implements DiffArtifactRepository {
        private final AtomicReference<DiffArtifact> artifact = new AtomicReference<>();
        private final AtomicInteger creates = new AtomicInteger();

        @Override
        public DiffArtifact create(DiffArtifact value) {
            creates.incrementAndGet();
            artifact.compareAndSet(null, value);
            return artifact.get();
        }

        @Override
        public Optional<DiffArtifact> findById(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                DiffArtifactId artifactId) {
            return artifact().filter(value -> value.id().equals(artifactId));
        }

        @Override
        public Optional<DiffArtifact> findByWorkspace(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                ExecutionWorkspaceId workspaceId) {
            return artifact().filter(value -> value.executionWorkspaceId().equals(workspaceId));
        }

        @Override
        public Optional<DiffArtifact> findByTaskExecution(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                TaskExecutionId taskExecutionId) {
            return artifact().filter(value -> value.taskExecutionId().equals(taskExecutionId));
        }

        private Optional<DiffArtifact> artifact() {
            return Optional.ofNullable(artifact.get());
        }
    }

    private static final class Fixture {
        private final WorkItemScope scope = new WorkItemScope(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(), WorkProjectId.generate());
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        private final RepositoryKey repositoryKey = new RepositoryKey("m4-i08-finalizer");
        private final CodingTargetSnapshotReference targetReference = new CodingTargetSnapshotReference(
                CodingTargetSnapshotId.generate(), 1, TaskFactHash.sha256("target"));
        private final ExecutionWorkspaceFingerprint fingerprint =
                new ExecutionWorkspaceFingerprint("f".repeat(64));
        private final WorkspaceArchiveReference archiveReference = WorkspaceArchiveReference.derive(
                ExecutionWorkspaceKey.derive(workspaceId, 1));
        private final ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        private final CodingTargetSnapshot target = mock(CodingTargetSnapshot.class);
        private final WorkspacePolicy policy = mock(WorkspacePolicy.class);
        private final Principal actor = mock(Principal.class);

        static Fixture create(RepositoryCommitId baseline) {
            Fixture fixture = new Fixture();
            when(fixture.workspace.scope()).thenReturn(fixture.scope);
            when(fixture.workspace.taskId()).thenReturn(fixture.taskId);
            when(fixture.workspace.taskExecutionId()).thenReturn(fixture.executionId);
            when(fixture.workspace.attempt()).thenReturn(1);
            when(fixture.workspace.id()).thenReturn(fixture.workspaceId);
            when(fixture.workspace.repositoryKey()).thenReturn(fixture.repositoryKey);
            when(fixture.workspace.codingTarget()).thenReturn(fixture.targetReference);
            when(fixture.workspace.baselineCommit()).thenReturn(baseline);
            when(fixture.workspace.archiveReference()).thenReturn(fixture.archiveReference);
            when(fixture.workspace.status()).thenReturn(ExecutionWorkspaceStatus.FINALIZING);
            when(fixture.workspace.version()).thenReturn(4L);
            when(fixture.workspace.fingerprint()).thenReturn(fixture.fingerprint);

            when(fixture.target.scope()).thenReturn(fixture.scope);
            when(fixture.target.taskId()).thenReturn(fixture.taskId);
            when(fixture.target.reference()).thenReturn(fixture.targetReference);
            when(fixture.target.baselineCommit()).thenReturn(baseline);
            when(fixture.target.allowedPaths()).thenReturn(CodingTargetAllowedPaths.of("src"));

            when(fixture.policy.scope()).thenReturn(fixture.scope);
            when(fixture.policy.taskId()).thenReturn(fixture.taskId);
            when(fixture.policy.taskExecutionId()).thenReturn(fixture.executionId);
            when(fixture.policy.attempt()).thenReturn(1);
            when(fixture.policy.codingTarget()).thenReturn(fixture.targetReference);
            when(fixture.policy.allowedPaths()).thenReturn(AllowedPathSet.of("src"));
            when(fixture.policy.operationBudget()).thenReturn(new WorkspaceOperationBudget(
                    20, 100, 1024 * 1024, 100, 4 * 1024 * 1024, 4 * 1024 * 1024, 3));

            when(fixture.actor.id()).thenReturn(PrincipalId.generate());
            when(fixture.actor.canAct()).thenReturn(true);
            when(fixture.actor.scope()).thenReturn(PrincipalScope.team(
                    fixture.scope.organizationId(), fixture.scope.teamId()));
            return fixture;
        }
    }
}
