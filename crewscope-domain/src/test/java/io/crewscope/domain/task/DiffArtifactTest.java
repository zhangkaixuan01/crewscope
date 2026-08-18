package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.DiffFileKind;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceCompletionReason;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceRetention;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingScope;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DiffArtifactTest {

    private static final UtcTimestamp TARGET_AT = UtcTimestamp.parse("2026-08-17T08:01:00Z");
    private static final UtcTimestamp READY_AT = UtcTimestamp.parse("2026-08-17T08:02:00Z");
    private static final UtcTimestamp CLAIM_AT = UtcTimestamp.parse("2026-08-17T08:03:00Z");
    private static final UtcTimestamp PREPARE_AT = UtcTimestamp.parse("2026-08-17T08:04:00Z");
    private static final UtcTimestamp ALLOCATE_AT = UtcTimestamp.parse("2026-08-17T08:05:00Z");
    private static final UtcTimestamp PROVISION_AT = UtcTimestamp.parse("2026-08-17T08:06:00Z");
    private static final UtcTimestamp WORKTREE_READY_AT =
            UtcTimestamp.parse("2026-08-17T08:07:00Z");
    private static final UtcTimestamp RUN_AT = UtcTimestamp.parse("2026-08-17T08:08:00Z");
    private static final UtcTimestamp ACTIVE_AT = UtcTimestamp.parse("2026-08-17T08:09:00Z");
    private static final UtcTimestamp FINALIZE_AT = UtcTimestamp.parse("2026-08-17T08:10:00Z");
    private static final UtcTimestamp LEASE_EXPIRES_AT =
            UtcTimestamp.parse("2026-08-17T09:00:00Z");
    private static final RepositoryCommitId DELIVERY_COMMIT =
            new RepositoryCommitId("b".repeat(40));

    @Test
    void publishesAnIntegrityClosedFinalArtifact() {
        Fixture fixture = Fixture.create();
        ExecutionWorkspace workspace = fixture.finalizingWorkspace();
        DiffManifest manifest = manifest();
        PatchArtifactReference patchArtifact = patchArtifact("full patch");
        DiffArtifactId artifactId = DiffArtifactId.generate();

        DiffArtifact artifact = DiffArtifact.publishFinal(
                artifactId,
                workspace,
                fixture.target,
                DELIVERY_COMMIT,
                manifest,
                patchArtifact,
                fixture.domain.owner,
                FINALIZE_AT);

        assertEquals(artifactId, artifact.id());
        assertEquals(workspace.scope(), artifact.scope());
        assertEquals(workspace.taskId(), artifact.taskId());
        assertEquals(workspace.taskExecutionId(), artifact.taskExecutionId());
        assertEquals(workspace.attempt(), artifact.attempt());
        assertEquals(workspace.id(), artifact.executionWorkspaceId());
        assertEquals(workspace.codingTarget(), artifact.codingTarget());
        assertEquals(workspace.baselineCommit(), artifact.baselineCommit());
        assertEquals(DELIVERY_COMMIT, artifact.deliveryCommit());
        assertEquals(manifest, artifact.manifest());
        assertEquals(patchArtifact, artifact.patchArtifact());
        assertEquals(artifact.id(), artifact.reference().id());
        assertEquals(artifact.finalHash(), artifact.reference().finalHash());
        assertEquals(fixture.domain.owner.id(), artifact.audit().createdBy().orElseThrow());
        assertEquals(FINALIZE_AT, artifact.audit().createdAt());

        DiffArtifact restored = reconstitute(artifact);
        assertEquals(artifact.finalHash(), restored.finalHash());
        assertEquals(artifact.reference(), restored.reference());
    }

    @Test
    void rejectsPublicationBeforeWorkspaceFinalizingAndAgainstAnotherTarget() {
        Fixture fixture = Fixture.create();
        ExecutionWorkspace active = fixture.activeWorkspace();
        DiffManifest manifest = manifest();
        PatchArtifactReference patchArtifact = patchArtifact("full patch");

        assertThrows(
                DomainValidationException.class,
                () -> DiffArtifact.publishFinal(
                        DiffArtifactId.generate(),
                        active,
                        fixture.target,
                        DELIVERY_COMMIT,
                        manifest,
                        patchArtifact,
                        fixture.domain.owner,
                        FINALIZE_AT));

        ExecutionWorkspace finalizing = active.beginFinalizing(
                ExecutionWorkspaceCompletionReason.SUCCEEDED,
                fixture.runningExecution(),
                3,
                fixture.domain.owner,
                FINALIZE_AT);
        CodingTargetSnapshot otherRevision = CodingTargetSnapshot.initial(
                CodingTargetSnapshotId.generate(),
                fixture.task,
                fixture.binding,
                new RepositoryBranchName("main"),
                fixture.target.baselineCommit(),
                fixture.target.allowedPaths(),
                fixture.target.buildProfile(),
                fixture.domain.owner,
                TARGET_AT);

        assertThrows(
                DomainValidationException.class,
                () -> DiffArtifact.publishFinal(
                        DiffArtifactId.generate(),
                        finalizing,
                        otherRevision,
                        DELIVERY_COMMIT,
                        manifest,
                        patchArtifact,
                        fixture.domain.owner,
                        FINALIZE_AT));
    }

    @Test
    void rejectsCurrentAndRenameSourcePathsOutsideCapturedAuthorization() {
        Fixture fixture = Fixture.create();
        ExecutionWorkspace workspace = fixture.finalizingWorkspace();
        DiffFileEntry outsideCurrent = text(
                "secret.txt", Optional.empty(), DiffFileKind.ADDED, 1, 0);
        DiffFileEntry outsideRenameSource = text(
                "docs/New.md", Optional.of("secret.txt"), DiffFileKind.RENAMED, 0, 0);

        for (DiffManifest unauthorized : List.of(
                DiffManifest.initial(List.of(outsideCurrent)),
                DiffManifest.initial(List.of(outsideRenameSource)))) {
            assertThrows(
                    DomainValidationException.class,
                    () -> DiffArtifact.publishFinal(
                            DiffArtifactId.generate(),
                            workspace,
                            fixture.target,
                            DELIVERY_COMMIT,
                            unauthorized,
                            patchArtifact("full patch"),
                            fixture.domain.owner,
                            FINALIZE_AT));
        }
    }

    @Test
    void requiresManifestAndFullPatchEmptinessToAgree() {
        Fixture fixture = Fixture.create();
        ExecutionWorkspace workspace = fixture.finalizingWorkspace();
        PatchArtifactReference emptyPatch = new PatchArtifactReference(
                ArtifactId.generate(), 0, RuntimeContentHash.sha256(""));

        assertThrows(
                DomainValidationException.class,
                () -> DiffArtifact.publishFinal(
                        DiffArtifactId.generate(),
                        workspace,
                        fixture.target,
                        DELIVERY_COMMIT,
                        manifest(),
                        emptyPatch,
                        fixture.domain.owner,
                        FINALIZE_AT));
        assertThrows(
                DomainValidationException.class,
                () -> DiffArtifact.publishFinal(
                        DiffArtifactId.generate(),
                        workspace,
                        fixture.target,
                        DELIVERY_COMMIT,
                        DiffManifest.initial(List.of()),
                        patchArtifact("non-empty"),
                        fixture.domain.owner,
                        FINALIZE_AT));
    }

    @Test
    void reconstitutionRejectsEveryFinalHashAuthorityTamper() {
        Fixture fixture = Fixture.create();
        DiffArtifact artifact = DiffArtifact.publishFinal(
                DiffArtifactId.generate(),
                fixture.finalizingWorkspace(),
                fixture.target,
                DELIVERY_COMMIT,
                manifest(),
                patchArtifact("full patch"),
                fixture.domain.owner,
                FINALIZE_AT);

        assertThrows(
                DomainValidationException.class,
                () -> reconstitute(
                        artifact,
                        artifact.executionWorkspaceId(),
                        new RepositoryCommitId("c".repeat(40)),
                        artifact.deliveryCommit(),
                        artifact.manifest(),
                        artifact.patchArtifact()));
        assertThrows(
                DomainValidationException.class,
                () -> reconstitute(
                        artifact,
                        artifact.executionWorkspaceId(),
                        artifact.baselineCommit(),
                        new RepositoryCommitId("c".repeat(40)),
                        artifact.manifest(),
                        artifact.patchArtifact()));
        assertThrows(
                DomainValidationException.class,
                () -> reconstitute(
                        artifact,
                        artifact.executionWorkspaceId(),
                        artifact.baselineCommit(),
                        artifact.deliveryCommit(),
                        DiffManifest.capture(
                                artifact.manifest().generation().next(),
                                artifact.manifest().files()),
                        artifact.patchArtifact()));
        assertThrows(
                DomainValidationException.class,
                () -> reconstitute(
                        artifact,
                        artifact.executionWorkspaceId(),
                        artifact.baselineCommit(),
                        artifact.deliveryCommit(),
                        artifact.manifest(),
                        patchArtifact("another full patch")));
        assertThrows(
                DomainValidationException.class,
                () -> reconstitute(
                        artifact,
                        ExecutionWorkspaceId.generate(),
                        artifact.baselineCommit(),
                        artifact.deliveryCommit(),
                        artifact.manifest(),
                        artifact.patchArtifact()));
    }

    @Test
    void exposesOnlyFinalFieldsAndNoInstanceMutationOperation() {
        assertTrue(Arrays.stream(DiffArtifact.class.getDeclaredFields())
                .allMatch(field -> Modifier.isFinal(field.getModifiers())));
        assertFalse(Arrays.stream(DiffArtifact.class.getDeclaredMethods())
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .map(Method::getReturnType)
                .anyMatch(DiffArtifact.class::equals));
        assertTrue(Arrays.stream(DiffArtifact.class.getDeclaredFields())
                .map(Field::getType)
                .noneMatch(java.nio.file.Path.class::isAssignableFrom));
    }

    private static DiffArtifact reconstitute(DiffArtifact artifact) {
        return reconstitute(
                artifact,
                artifact.executionWorkspaceId(),
                artifact.baselineCommit(),
                artifact.deliveryCommit(),
                artifact.manifest(),
                artifact.patchArtifact());
    }

    private static DiffArtifact reconstitute(
            DiffArtifact artifact,
            ExecutionWorkspaceId workspaceId,
            RepositoryCommitId baselineCommit,
            RepositoryCommitId deliveryCommit,
            DiffManifest manifest,
            PatchArtifactReference patchArtifact) {
        return DiffArtifact.reconstitute(
                artifact.id(),
                artifact.scope(),
                artifact.taskId(),
                artifact.taskExecutionId(),
                artifact.attempt(),
                workspaceId,
                artifact.codingTarget(),
                baselineCommit,
                deliveryCommit,
                manifest,
                patchArtifact,
                artifact.finalHash(),
                artifact.audit());
    }

    private static DiffManifest manifest() {
        return DiffManifest.initial(List.of(text(
                "src/Greeting.java", Optional.empty(), DiffFileKind.MODIFIED, 2, 2)));
    }

    private static DiffFileEntry text(
            String path,
            Optional<String> oldPath,
            DiffFileKind kind,
            long additions,
            long deletions) {
        String completePatch = path + ":" + kind + ":" + additions + ":" + deletions;
        return DiffFileEntry.text(
                path,
                oldPath,
                kind,
                additions,
                deletions,
                false,
                RuntimeContentHash.sha256(completePatch),
                Optional.of(completePatch));
    }

    private static PatchArtifactReference patchArtifact(String content) {
        return new PatchArtifactReference(
                ArtifactId.generate(),
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                RuntimeContentHash.sha256(content));
    }

    private static ExecutionLease lease(
            TaskExecution execution, ExecutionLeasePhase phase, int version) {
        return ExecutionLease.reconstitute(
                ExecutionLeaseId.generate(),
                execution.scope().organizationId(),
                new RuntimeEnvironment("test"),
                execution.id(),
                execution.attempt(),
                ExecutionRuntimeId.generate(),
                RuntimeWorkerId.generate(),
                new ClaimTokenHash("d".repeat(64)),
                FencingToken.initial(),
                phase,
                PREPARE_AT,
                phase == ExecutionLeasePhase.RUN ? RUN_AT : PREPARE_AT,
                LEASE_EXPIRES_AT,
                Optional.empty(),
                version);
    }

    private record Fixture(
            TaskDomainFixture domain,
            Task task,
            RepositoryBinding binding,
            CodingTargetSnapshot target,
            TaskExecution preparingExecution,
            ExecutionLease prepareLease) {

        private static Fixture create() {
            TaskDomainFixture domain = new TaskDomainFixture();
            Task task = Task.create(
                    TaskId.generate(),
                    domain.workItem,
                    TaskSource.fromWorkItem(domain.workItem),
                    new TaskBrief("Implement Diff finalization", List.of("Final Patch is closed")),
                    domain.snapshot(),
                    domain.owner,
                    TaskDomainFixture.CREATED_AT);
            RepositoryBinding binding = RepositoryBinding.reconstitute(
                    RepositoryBindingId.generate(),
                    new RepositoryBindingScope(
                            task.scope().organizationId(),
                            task.scope().teamId(),
                            task.scope().workspaceId(),
                            task.scope().projectId()),
                    RepositoryKind.LOCAL_MANAGED,
                    new RepositoryKey("crewscope-java"),
                    new RepositoryBranchName("main"),
                    RepositoryBindingStatus.ACTIVE,
                    2,
                    AuditMetadata.createdBy(domain.owner.id(), TaskDomainFixture.CREATED_AT));
            CodingTargetSnapshot target = CodingTargetSnapshot.initial(
                    CodingTargetSnapshotId.generate(),
                    task,
                    binding,
                    new RepositoryBranchName("main"),
                    new RepositoryCommitId("a".repeat(40)),
                    CodingTargetAllowedPaths.of("README.md", "docs", "obsolete.txt", "src"),
                    new BuildProfileReference(
                            "maven-java-17", 1, TaskFactHash.sha256("maven-java-17-v1")),
                    domain.owner,
                    TARGET_AT);
            TaskExecution execution = TaskExecution.firstAttempt(
                            TaskExecutionId.generate(),
                            task,
                            3,
                            TaskExecutionPriority.NORMAL,
                            TaskDomainFixture.CREATED_AT,
                            domain.owner,
                            TaskDomainFixture.CREATED_AT)
                    .markReady(0, domain.owner, READY_AT)
                    .claim(1, domain.owner, CLAIM_AT)
                    .beginPreparing(2, domain.owner, PREPARE_AT);
            return new Fixture(domain, task, binding, target, execution, lease(
                    execution, ExecutionLeasePhase.PREPARE, 0));
        }

        private TaskExecution runningExecution() {
            return preparingExecution.beginRunning(3, domain.owner, RUN_AT);
        }

        private ExecutionWorkspace activeWorkspace() {
            TaskExecution running = runningExecution();
            ExecutionLease runLease = ExecutionLease.reconstitute(
                    prepareLease.id(),
                    prepareLease.organizationId(),
                    prepareLease.environment(),
                    prepareLease.taskExecutionId(),
                    prepareLease.attempt(),
                    prepareLease.runtimeId(),
                    prepareLease.workerId(),
                    prepareLease.claimTokenHash(),
                    prepareLease.fencingToken(),
                    ExecutionLeasePhase.RUN,
                    prepareLease.acquiredAt(),
                    RUN_AT,
                    LEASE_EXPIRES_AT,
                    Optional.empty(),
                    1);
            return ExecutionWorkspace.allocate(
                            ExecutionWorkspaceId.generate(),
                            target,
                            preparingExecution,
                            prepareLease,
                            new ExecutionWorkspaceRetention(LEASE_EXPIRES_AT),
                            domain.owner,
                            ALLOCATE_AT)
                    .beginProvisioning(
                            preparingExecution,
                            prepareLease,
                            0,
                            domain.owner,
                            PROVISION_AT)
                    .markReady(
                            preparingExecution,
                            prepareLease,
                            1,
                            domain.owner,
                            WORKTREE_READY_AT)
                    .activate(running, runLease, 2, domain.owner, ACTIVE_AT);
        }

        private ExecutionWorkspace finalizingWorkspace() {
            return activeWorkspace().beginFinalizing(
                    ExecutionWorkspaceCompletionReason.SUCCEEDED,
                    runningExecution(),
                    3,
                    domain.owner,
                    FINALIZE_AT);
        }
    }
}
