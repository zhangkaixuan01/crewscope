package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotChangeReason;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingScope;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodingTargetSnapshotTest {

    private static final RepositoryCommitId BASELINE_COMMIT =
            new RepositoryCommitId("a".repeat(40));
    private static final RepositoryCommitId RETRY_COMMIT =
            new RepositoryCommitId("b".repeat(40));
    private static final BuildProfileReference MAVEN_PROFILE = new BuildProfileReference(
            "maven-java-17", 3, TaskFactHash.sha256("maven-java-17-v3"));

    @Test
    void capturesTheCompleteImmutableTargetAndCanonicalHash() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        Task task = codingTask(fixture);
        RepositoryBinding binding = binding(task, fixture, RepositoryBindingStatus.ACTIVE, 4);
        CodingTargetSnapshotId snapshotId = CodingTargetSnapshotId.generate();

        CodingTargetSnapshot snapshot = CodingTargetSnapshot.initial(
                snapshotId,
                task,
                binding,
                new RepositoryBranchName("release/2026.08"),
                BASELINE_COMMIT,
                CodingTargetAllowedPaths.of("crewscope-domain", "docs"),
                MAVEN_PROFILE,
                fixture.owner,
                TaskDomainFixture.LATER);

        assertEquals(snapshotId, snapshot.id());
        assertEquals(task.scope(), snapshot.scope());
        assertEquals(task.id(), snapshot.taskId());
        assertEquals(task.brief().contentHash(), snapshot.taskBriefHash());
        assertEquals(1, snapshot.revision());
        assertEquals(Optional.empty(), snapshot.parentSnapshotId());
        assertEquals(CodingTargetSnapshotChangeReason.TASK_CREATED, snapshot.changeReason());
        assertEquals(binding.id(), snapshot.repositoryBindingId());
        assertEquals(4, snapshot.repositoryBindingVersion());
        assertEquals(RepositoryKind.LOCAL_MANAGED, snapshot.repositoryKind());
        assertEquals(binding.repositoryKey(), snapshot.repositoryKey());
        assertEquals("release/2026.08", snapshot.baselineRef().value());
        assertEquals(BASELINE_COMMIT, snapshot.baselineCommit());
        assertEquals(List.of("crewscope-domain", "docs"), snapshot.allowedPaths().values());
        assertEquals(MAVEN_PROFILE, snapshot.buildProfile());
        assertEquals(task.brief().acceptanceCriteria(), snapshot.acceptanceCriteria());
        assertEquals(fixture.owner.id(), snapshot.createdByPrincipalId());
        assertEquals(TaskDomainFixture.LATER, snapshot.createdAt());
        assertEquals(snapshot.snapshotHash(), reconstitute(snapshot, snapshot.snapshotHash()).snapshotHash());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.acceptanceCriteria().add("mutated"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.allowedPaths().values().add("mutated"));
    }

    @Test
    void freezesTheResolvedCommitEvenWhenTheSelectedRefMoves() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        Task task = codingTask(fixture);
        RepositoryBinding binding = binding(task, fixture, RepositoryBindingStatus.ACTIVE, 4);
        CodingTargetSnapshot snapshot = initial(task, binding, fixture);

        RepositoryBinding changed = binding.changeDefaultBranch(
                new RepositoryBranchName("develop"),
                4,
                fixture.owner,
                TaskDomainFixture.LATER);
        RepositoryCommitId movedRefCommit = new RepositoryCommitId("c".repeat(40));

        assertEquals("develop", changed.defaultBranch().value());
        assertEquals(5, changed.version());
        assertNotEquals(movedRefCommit, snapshot.baselineCommit());
        assertEquals("main", snapshot.baselineRef().value());
        assertEquals(BASELINE_COMMIT, snapshot.baselineCommit());
        assertEquals(4, snapshot.repositoryBindingVersion());
    }

    @Test
    void rejectsAnInactiveBindingOrAnyCompleteScopeMismatch() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        Task task = codingTask(fixture);
        RepositoryBinding disabled = binding(task, fixture, RepositoryBindingStatus.DISABLED, 5);
        RepositoryBinding otherWorkspace = RepositoryBinding.reconstitute(
                RepositoryBindingId.generate(),
                new RepositoryBindingScope(
                        task.scope().organizationId(),
                        task.scope().teamId(),
                        WorkspaceId.generate(),
                        task.scope().projectId()),
                RepositoryKind.LOCAL_MANAGED,
                new RepositoryKey("crewscope-java"),
                new RepositoryBranchName("main"),
                RepositoryBindingStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(fixture.owner.id(), TaskDomainFixture.CREATED_AT));

        assertThrows(DomainValidationException.class, () -> initial(task, disabled, fixture));
        assertThrows(DomainValidationException.class, () -> initial(task, otherWorkspace, fixture));

        Task activeTask = task.switchCurrentExecution(
                Optional.empty(),
                TaskExecutionId.generate(),
                0,
                fixture.owner,
                TaskDomainFixture.LATER);
        assertThrows(
                DomainValidationException.class,
                () -> initial(
                        activeTask,
                        binding(task, fixture, RepositoryBindingStatus.ACTIVE, 0),
                        fixture));
    }

    @Test
    void validatesFullLowercaseGitCommitIdentities() {
        assertEquals("0".repeat(40), new RepositoryCommitId("0".repeat(40)).value());

        for (String invalid : List.of(
                "a".repeat(39),
                "a".repeat(41),
                "A".repeat(40),
                "g".repeat(40),
                "refs/heads/main")) {
            assertThrows(DomainValidationException.class, () -> new RepositoryCommitId(invalid));
        }
    }

    @Test
    void canonicalizesAllowedPathsAndRejectsEscapeForms() {
        CodingTargetAllowedPaths paths = new CodingTargetAllowedPaths(
                List.of("src/test", "src", "docs", "src", "资源"));

        assertEquals(List.of("docs", "src", "资源"), paths.values());
        assertTrue(paths.allows("src/main/java/App.java"));
        assertTrue(paths.containsAll(CodingTargetAllowedPaths.of("src/main", "docs/api")));
        assertEquals(List.of("."), new CodingTargetAllowedPaths(List.of("src", ".", "docs")).values());

        for (String invalid : List.of(
                "",
                "/etc/passwd",
                "../secret",
                "src/../secret",
                "src/./main",
                "src//main",
                "src\\main",
                "C:/workspace",
                "src/\u0000secret")) {
            assertThrows(
                    DomainValidationException.class,
                    () -> CodingTargetAllowedPaths.of(invalid));
        }
    }

    @Test
    void retryReusesTheExactClosedSnapshotReferenceByDefault() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        Task task = codingTask(fixture);
        CodingTargetSnapshot snapshot = initial(
                task,
                binding(task, fixture, RepositoryBindingStatus.ACTIVE, 0),
                fixture);
        Task failedTask = fail(task, fixture);

        assertEquals(snapshot.reference(), snapshot.reuseForRetry(failedTask));
        assertEquals(1, snapshot.reuseForRetry(failedTask).revision());
        assertEquals(snapshot.snapshotHash(), snapshot.reuseForRetry(failedTask).snapshotHash());
    }

    @Test
    void retryCanCreateALinearRevisionWhileOnlyNarrowingPathAuthorization() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        Task task = codingTask(fixture);
        RepositoryBinding firstBinding = binding(task, fixture, RepositoryBindingStatus.ACTIVE, 0);
        CodingTargetSnapshot first = initial(task, firstBinding, fixture);
        Task failedTask = fail(task, fixture);
        RepositoryBinding currentBinding = binding(task, fixture, RepositoryBindingStatus.ACTIVE, 1);

        CodingTargetSnapshot retry = CodingTargetSnapshot.supersedeForRetry(
                CodingTargetSnapshotId.generate(),
                first,
                failedTask,
                currentBinding,
                new RepositoryBranchName("fix/retry"),
                RETRY_COMMIT,
                CodingTargetAllowedPaths.of("crewscope-domain/src/main"),
                MAVEN_PROFILE,
                fixture.owner,
                TaskDomainFixture.LATER);

        assertEquals(2, retry.revision());
        assertEquals(Optional.of(first.id()), retry.parentSnapshotId());
        assertEquals(
                CodingTargetSnapshotChangeReason.RETRY_TARGET_UPDATED,
                retry.changeReason());
        assertEquals(1, retry.repositoryBindingVersion());
        assertEquals(RETRY_COMMIT, retry.baselineCommit());
        assertEquals(List.of("crewscope-domain/src/main"), retry.allowedPaths().values());
        assertNotEquals(first.snapshotHash(), retry.snapshotHash());

        assertThrows(
                DomainValidationException.class,
                () -> CodingTargetSnapshot.supersedeForRetry(
                        CodingTargetSnapshotId.generate(),
                        first,
                        failedTask,
                        currentBinding,
                        new RepositoryBranchName("fix/retry"),
                        RETRY_COMMIT,
                        CodingTargetAllowedPaths.of("."),
                        MAVEN_PROFILE,
                        fixture.owner,
                        TaskDomainFixture.LATER));
    }

    @Test
    void retryRejectsNoopReplacementAndAChangedTaskBrief() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        Task task = codingTask(fixture);
        RepositoryBinding binding = binding(task, fixture, RepositoryBindingStatus.ACTIVE, 0);
        CodingTargetSnapshot first = initial(task, binding, fixture);
        Task failedTask = fail(task, fixture);

        assertThrows(
                DomainValidationException.class,
                () -> CodingTargetSnapshot.supersedeForRetry(
                        CodingTargetSnapshotId.generate(),
                        first,
                        failedTask,
                        binding,
                        first.baselineRef(),
                        first.baselineCommit(),
                        first.allowedPaths(),
                        first.buildProfile(),
                        fixture.owner,
                        TaskDomainFixture.LATER));

        Task changedBrief = Task.reconstitute(
                failedTask.id(),
                failedTask.scope(),
                failedTask.workItemId(),
                failedTask.source(),
                new TaskBrief("Changed objective", List.of("Changed criterion")),
                failedTask.responsibilitySnapshot(),
                failedTask.status(),
                failedTask.currentExecutionId(),
                failedTask.cancellation(),
                failedTask.version(),
                failedTask.audit());
        assertThrows(DomainValidationException.class, () -> first.reuseForRetry(changedBrief));
    }

    @Test
    void rejectsAReconstitutedSnapshotWhoseCanonicalFactsWereTamperedWith() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        Task task = codingTask(fixture);
        CodingTargetSnapshot snapshot = initial(
                task,
                binding(task, fixture, RepositoryBindingStatus.ACTIVE, 0),
                fixture);

        assertThrows(
                DomainValidationException.class,
                () -> reconstitute(snapshot, TaskFactHash.sha256("tampered")));
    }

    private static Task codingTask(TaskDomainFixture fixture) {
        return Task.create(
                TaskId.generate(),
                fixture.workItem,
                TaskSource.fromWorkItem(fixture.workItem),
                new TaskBrief(
                        "Implement the CodingTarget snapshot",
                        List.of("All allowed-path checks pass", "The module test suite passes")),
                fixture.snapshot(),
                fixture.owner,
                TaskDomainFixture.CREATED_AT);
    }

    private static RepositoryBinding binding(
            Task task,
            TaskDomainFixture fixture,
            RepositoryBindingStatus status,
            long version) {
        return RepositoryBinding.reconstitute(
                RepositoryBindingId.generate(),
                new RepositoryBindingScope(
                        task.scope().organizationId(),
                        task.scope().teamId(),
                        task.scope().workspaceId(),
                        task.scope().projectId()),
                RepositoryKind.LOCAL_MANAGED,
                new RepositoryKey("crewscope-java"),
                new RepositoryBranchName("main"),
                status,
                version,
                AuditMetadata.createdBy(fixture.owner.id(), TaskDomainFixture.CREATED_AT));
    }

    private static CodingTargetSnapshot initial(
            Task task, RepositoryBinding binding, TaskDomainFixture fixture) {
        return CodingTargetSnapshot.initial(
                CodingTargetSnapshotId.generate(),
                task,
                binding,
                new RepositoryBranchName("main"),
                BASELINE_COMMIT,
                CodingTargetAllowedPaths.of("crewscope-domain", "docs"),
                MAVEN_PROFILE,
                fixture.owner,
                TaskDomainFixture.LATER);
    }

    private static Task fail(Task task, TaskDomainFixture fixture) {
        TaskExecutionId executionId = TaskExecutionId.generate();
        return task.switchCurrentExecution(
                        Optional.empty(),
                        executionId,
                        0,
                        fixture.owner,
                        TaskDomainFixture.LATER)
                .synchronizeStatus(
                        executionId,
                        TaskStatus.FAILED,
                        1,
                        fixture.owner,
                        TaskDomainFixture.LATER);
    }

    private static CodingTargetSnapshot reconstitute(
            CodingTargetSnapshot snapshot, TaskFactHash expectedHash) {
        return CodingTargetSnapshot.reconstitute(
                snapshot.id(),
                snapshot.scope(),
                snapshot.taskId(),
                snapshot.taskBriefHash(),
                snapshot.revision(),
                snapshot.parentSnapshotId(),
                snapshot.changeReason(),
                snapshot.repositoryBindingId(),
                snapshot.repositoryBindingVersion(),
                snapshot.repositoryKind(),
                snapshot.repositoryKey(),
                snapshot.baselineRef(),
                snapshot.baselineCommit(),
                snapshot.allowedPaths(),
                snapshot.buildProfile(),
                snapshot.acceptanceCriteria(),
                expectedHash,
                snapshot.createdByPrincipalId(),
                snapshot.createdAt());
    }
}
