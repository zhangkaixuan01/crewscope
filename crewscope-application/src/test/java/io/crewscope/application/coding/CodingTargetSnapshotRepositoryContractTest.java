package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotRevisionConflictException;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingScope;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.task.TaskResponsibilitySnapshotEntry;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.task.TaskSourceType;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Executable isolation and uniqueness contract for the future PostgreSQL snapshot adapter. */
class CodingTargetSnapshotRepositoryContractTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-17T05:00:00Z");

    @Test
    void atomicallyRejectsDuplicateTaskRevisionsAndReturnsTheLatestRevision() {
        InMemoryRepository repository = new InMemoryRepository();
        Fixture fixture = Fixture.create();
        CodingTargetSnapshot first = fixture.initial();
        CodingTargetSnapshot retry = fixture.retry(first);

        repository.create(first);
        repository.create(retry);

        CodingTargetSnapshotRevisionConflictException failure = assertThrows(
                CodingTargetSnapshotRevisionConflictException.class,
                () -> repository.create(first));
        assertEquals(
                DomainErrorCode.CODING_TARGET_SNAPSHOT_REVISION_CONFLICT,
                failure.error().code());
        assertEquals(
                retry.id(),
                repository
                        .findLatestByTask(
                                fixture.scope.organizationId(),
                                fixture.scope.teamId(),
                                fixture.scope.projectId(),
                                fixture.task.id())
                        .orElseThrow()
                        .id());
        assertEquals(
                List.of(1L, 2L),
                repository
                        .findByTask(
                                fixture.scope.organizationId(),
                                fixture.scope.teamId(),
                                fixture.scope.projectId(),
                                fixture.task.id())
                        .stream()
                        .map(CodingTargetSnapshot::revision)
                        .toList());
    }

    @Test
    void isolatesEveryLookupByOrganizationTeamAndWorkProject() {
        InMemoryRepository repository = new InMemoryRepository();
        Fixture fixture = Fixture.create();
        CodingTargetSnapshot snapshot = repository.create(fixture.initial());

        assertEquals(
                snapshot.id(),
                repository
                        .findById(
                                fixture.scope.organizationId(),
                                fixture.scope.teamId(),
                                fixture.scope.projectId(),
                                snapshot.id())
                        .orElseThrow()
                        .id());
        assertEquals(
                Optional.empty(),
                repository.findById(
                        OrganizationId.generate(),
                        fixture.scope.teamId(),
                        fixture.scope.projectId(),
                        snapshot.id()));
        assertEquals(
                Optional.empty(),
                repository.findLatestByTask(
                        fixture.scope.organizationId(),
                        TeamId.generate(),
                        fixture.scope.projectId(),
                        fixture.task.id()));
        assertEquals(
                List.of(),
                repository.findByTask(
                        fixture.scope.organizationId(),
                        fixture.scope.teamId(),
                        WorkProjectId.generate(),
                        fixture.task.id()));
    }

    @Test
    void treatsATaskWithoutASnapshotAsACompatibleNonCodingTask() {
        Fixture fixture = Fixture.create();

        assertEquals(
                Optional.empty(),
                new InMemoryRepository().findLatestByTask(
                        fixture.scope.organizationId(),
                        fixture.scope.teamId(),
                        fixture.scope.projectId(),
                        TaskId.generate()));
    }

    private static final class InMemoryRepository implements CodingTargetSnapshotRepository {

        private final List<CodingTargetSnapshot> values = new ArrayList<>();

        @Override
        public CodingTargetSnapshot create(CodingTargetSnapshot snapshot) {
            values.stream()
                    .filter(value -> value.taskId().equals(snapshot.taskId()))
                    .filter(value -> value.revision() == snapshot.revision())
                    .findAny()
                    .ifPresent(ignored -> {
                        throw new CodingTargetSnapshotRevisionConflictException(
                                snapshot.taskId(), snapshot.revision());
                    });
            values.add(snapshot);
            return snapshot;
        }

        @Override
        public Optional<CodingTargetSnapshot> findById(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                CodingTargetSnapshotId snapshotId) {
            return values.stream()
                    .filter(value -> matches(value, organizationId, teamId, workProjectId))
                    .filter(value -> value.id().equals(snapshotId))
                    .findFirst();
        }

        @Override
        public Optional<CodingTargetSnapshot> findLatestByTask(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                TaskId taskId) {
            return values.stream()
                    .filter(value -> matches(value, organizationId, teamId, workProjectId))
                    .filter(value -> value.taskId().equals(taskId))
                    .max(Comparator.comparingLong(CodingTargetSnapshot::revision));
        }

        @Override
        public List<CodingTargetSnapshot> findByTask(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                TaskId taskId) {
            return values.stream()
                    .filter(value -> matches(value, organizationId, teamId, workProjectId))
                    .filter(value -> value.taskId().equals(taskId))
                    .sorted(Comparator.comparingLong(CodingTargetSnapshot::revision))
                    .toList();
        }

        private static boolean matches(
                CodingTargetSnapshot value,
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId) {
            return value.scope().organizationId().equals(organizationId)
                    && value.scope().teamId().equals(teamId)
                    && value.scope().projectId().equals(workProjectId);
        }
    }

    private record Fixture(WorkItemScope scope, Principal owner, Task task, RepositoryBinding binding) {

        private static Fixture create() {
            WorkItemScope scope = new WorkItemScope(
                    OrganizationId.generate(),
                    TeamId.generate(),
                    WorkspaceId.generate(),
                    WorkProjectId.generate());
            PrincipalId ownerId = PrincipalId.generate();
            Principal owner = Principal.create(
                    ownerId,
                    PrincipalScope.team(scope.organizationId(), scope.teamId()),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Owner",
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    NOW);
            WorkItemId workItemId = WorkItemId.generate();
            TaskResponsibilitySnapshot responsibilitySnapshot = new TaskResponsibilitySnapshot(
                    scope,
                    workItemId,
                    List.of(
                            entry(ResponsibilityRole.OWNER, ownerId, PrincipalType.USER),
                            entry(
                                    ResponsibilityRole.EXECUTOR,
                                    PrincipalId.generate(),
                                    PrincipalType.SPECIALIST_AGENT)),
                    NOW);
            Task task = Task.reconstitute(
                    TaskId.generate(),
                    scope,
                    workItemId,
                    new TaskSource(
                            TaskSourceType.WORK_ITEM,
                            scope,
                            workItemId,
                            0,
                            Optional.empty(),
                            Optional.empty()),
                    new TaskBrief("Implement snapshot persistence", List.of("All tests pass")),
                    responsibilitySnapshot,
                    TaskStatus.CREATED,
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    AuditMetadata.createdBy(ownerId, NOW));
            RepositoryBinding binding = RepositoryBinding.reconstitute(
                    RepositoryBindingId.generate(),
                    new RepositoryBindingScope(
                            scope.organizationId(),
                            scope.teamId(),
                            scope.workspaceId(),
                            scope.projectId()),
                    RepositoryKind.LOCAL_MANAGED,
                    new RepositoryKey("crewscope-java"),
                    new RepositoryBranchName("main"),
                    RepositoryBindingStatus.ACTIVE,
                    0,
                    AuditMetadata.createdBy(ownerId, NOW));
            return new Fixture(scope, owner, task, binding);
        }

        private CodingTargetSnapshot initial() {
            return CodingTargetSnapshot.initial(
                    CodingTargetSnapshotId.generate(),
                    task,
                    binding,
                    new RepositoryBranchName("main"),
                    new RepositoryCommitId("a".repeat(40)),
                    CodingTargetAllowedPaths.of("src"),
                    new BuildProfileReference("maven", 1, TaskFactHash.sha256("maven-v1")),
                    owner,
                    NOW);
        }

        private CodingTargetSnapshot retry(CodingTargetSnapshot parent) {
            TaskExecutionId executionId = TaskExecutionId.generate();
            Task failedTask = task.switchCurrentExecution(
                            Optional.empty(), executionId, 0, owner, NOW)
                    .synchronizeStatus(executionId, TaskStatus.FAILED, 1, owner, NOW);
            return CodingTargetSnapshot.supersedeForRetry(
                    CodingTargetSnapshotId.generate(),
                    parent,
                    failedTask,
                    binding,
                    new RepositoryBranchName("retry"),
                    new RepositoryCommitId("b".repeat(40)),
                    CodingTargetAllowedPaths.of("src/main"),
                    parent.buildProfile(),
                    owner,
                    NOW);
        }

        private static TaskResponsibilitySnapshotEntry entry(
                ResponsibilityRole role, PrincipalId principalId, PrincipalType principalType) {
            return new TaskResponsibilitySnapshotEntry(
                    ResponsibilityAssignmentId.generate(),
                    0,
                    role,
                    principalId,
                    principalType,
                    principalType == PrincipalType.USER
                            ? Optional.of(TeamMemberId.generate())
                            : Optional.empty(),
                    NOW,
                    NOW);
        }
    }
}
