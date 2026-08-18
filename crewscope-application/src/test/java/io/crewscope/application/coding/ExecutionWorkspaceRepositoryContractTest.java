package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceAttemptConflictException;
import io.crewscope.domain.coding.ExecutionWorkspaceFailure;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceRetention;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
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
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ClaimTokenHash;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionLeasePhase;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPriority;
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

/** Executable uniqueness, scope, locking and worker-batch contract for the PostgreSQL adapter. */
class ExecutionWorkspaceRepositoryContractTest {

    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-17T05:00:00Z");
    private static final UtcTimestamp READY_AT = UtcTimestamp.parse("2026-08-17T05:01:00Z");
    private static final UtcTimestamp CLAIM_AT = UtcTimestamp.parse("2026-08-17T05:02:00Z");
    private static final UtcTimestamp PREPARE_AT = UtcTimestamp.parse("2026-08-17T05:03:00Z");
    private static final UtcTimestamp ALLOCATE_AT = UtcTimestamp.parse("2026-08-17T05:04:00Z");
    private static final UtcTimestamp RUN_AT = UtcTimestamp.parse("2026-08-17T05:05:00Z");
    private static final UtcTimestamp RECOVER_AT = UtcTimestamp.parse("2026-08-17T05:06:00Z");
    private static final UtcTimestamp QUERY_AT = UtcTimestamp.parse("2026-08-17T06:30:00Z");
    private static final RuntimeEnvironment ENVIRONMENT = new RuntimeEnvironment("test");

    @Test
    void atomicallyRejectsASecondWorkspaceForTheSameTaskExecutionAttempt() {
        InMemoryRepository repository = new InMemoryRepository();
        Fixture fixture = Fixture.create(UtcTimestamp.parse("2026-08-17T07:00:00Z"));
        ExecutionWorkspace workspace = repository.create(fixture.allocate());

        ExecutionWorkspaceAttemptConflictException failure = assertThrows(
                ExecutionWorkspaceAttemptConflictException.class,
                () -> repository.create(fixture.allocate()));

        assertEquals(
                DomainErrorCode.EXECUTION_WORKSPACE_ATTEMPT_CONFLICT,
                failure.error().code());
        assertEquals(
                workspace.id(),
                repository
                        .findByTaskExecution(
                                fixture.scope.organizationId(),
                                fixture.scope.teamId(),
                                fixture.scope.projectId(),
                                fixture.execution.id())
                        .orElseThrow()
                        .id());
    }

    @Test
    void isolatesLookupsByOrganizationTeamAndWorkProject() {
        InMemoryRepository repository = new InMemoryRepository();
        Fixture fixture = Fixture.create(UtcTimestamp.parse("2026-08-17T07:00:00Z"));
        ExecutionWorkspace workspace = repository.create(fixture.allocate());

        assertEquals(
                workspace.id(),
                repository
                        .findById(
                                fixture.scope.organizationId(),
                                fixture.scope.teamId(),
                                fixture.scope.projectId(),
                                workspace.id())
                        .orElseThrow()
                        .id());
        assertEquals(
                Optional.empty(),
                repository.findById(
                        OrganizationId.generate(),
                        fixture.scope.teamId(),
                        fixture.scope.projectId(),
                        workspace.id()));
        assertEquals(
                Optional.empty(),
                repository.findByTaskExecution(
                        fixture.scope.organizationId(),
                        TeamId.generate(),
                        fixture.scope.projectId(),
                        fixture.execution.id()));
        assertEquals(
                Optional.empty(),
                repository.findById(
                        fixture.scope.organizationId(),
                        fixture.scope.teamId(),
                        WorkProjectId.generate(),
                        workspace.id()));
    }

    @Test
    void updatesOnlyAgainstThePreviouslyCommittedAggregateVersion() {
        InMemoryRepository repository = new InMemoryRepository();
        Fixture fixture = Fixture.create(UtcTimestamp.parse("2026-08-17T07:00:00Z"));
        ExecutionWorkspace pending = repository.create(fixture.allocate());
        ExecutionWorkspace provisioning = pending.beginProvisioning(
                fixture.execution,
                fixture.prepareLease,
                0,
                fixture.owner,
                UtcTimestamp.parse("2026-08-17T05:04:10Z"));
        ExecutionWorkspace staleFailure = pending.fail(
                new ExecutionWorkspaceFailure("STALE_FAILURE"),
                0,
                fixture.owner,
                UtcTimestamp.parse("2026-08-17T05:04:20Z"));

        repository.update(provisioning);

        OptimisticLockConflictException failure = assertThrows(
                OptimisticLockConflictException.class,
                () -> repository.update(staleFailure));
        assertEquals(DomainErrorCode.OPTIMISTIC_LOCK_CONFLICT, failure.error().code());
        assertEquals(
                ExecutionWorkspaceStatus.PROVISIONING,
                repository
                        .findById(
                                fixture.scope.organizationId(),
                                fixture.scope.teamId(),
                                fixture.scope.projectId(),
                                pending.id())
                        .orElseThrow()
                        .status());
    }

    @Test
    void returnsBoundedLockedRecoveryAndRetentionWorkForTheOwningOrganization() {
        InMemoryRepository repository = new InMemoryRepository();
        Fixture recoveringFixture =
                Fixture.create(UtcTimestamp.parse("2026-08-17T07:00:00Z"));
        Fixture dueFixture = Fixture.create(UtcTimestamp.parse("2026-08-17T06:00:00Z"));
        Fixture futureFixture = Fixture.create(UtcTimestamp.parse("2026-08-17T07:00:00Z"));
        ExecutionWorkspace recovering = repository.create(recoveringFixture.recovering());
        ExecutionWorkspace due = repository.create(dueFixture.failed());
        repository.create(futureFixture.failed());

        assertEquals(
                List.of(recovering.id()),
                repository
                        .findRecoveringForUpdate(
                                recovering.scope().organizationId(), ENVIRONMENT, 1)
                        .stream()
                        .map(ExecutionWorkspace::id)
                        .toList());
        assertEquals(
                List.of(),
                repository.findRecoveringForUpdate(
                        OrganizationId.generate(), ENVIRONMENT, 1));
        assertEquals(
                List.of(due.id()),
                repository
                        .findRetentionDueForUpdate(
                                due.scope().organizationId(), QUERY_AT, 1)
                        .stream()
                        .map(ExecutionWorkspace::id)
                        .toList());
        assertEquals(
                List.of(),
                repository.findRetentionDueForUpdate(
                        futureFixture.scope.organizationId(),
                        UtcTimestamp.parse("2026-08-17T06:59:59Z"),
                        1));
    }

    private static final class InMemoryRepository implements ExecutionWorkspaceRepository {

        private final List<ExecutionWorkspace> values = new ArrayList<>();

        @Override
        public ExecutionWorkspace create(ExecutionWorkspace workspace) {
            values.stream()
                    .filter(value -> value.taskExecutionId().equals(workspace.taskExecutionId()))
                    .filter(value -> value.attempt() == workspace.attempt())
                    .findAny()
                    .ifPresent(ignored -> {
                        throw new ExecutionWorkspaceAttemptConflictException(
                                workspace.taskExecutionId(), workspace.attempt());
                    });
            values.add(workspace);
            return workspace;
        }

        @Override
        public ExecutionWorkspace update(ExecutionWorkspace workspace) {
            for (int index = 0; index < values.size(); index++) {
                ExecutionWorkspace current = values.get(index);
                if (!current.id().equals(workspace.id())) {
                    continue;
                }
                long expectedVersion = workspace.version() - 1;
                if (current.version() != expectedVersion) {
                    throw new OptimisticLockConflictException(
                            "ExecutionWorkspace",
                            workspace.id(),
                            expectedVersion,
                            current.version());
                }
                values.set(index, workspace);
                return workspace;
            }
            throw new IllegalStateException("ExecutionWorkspace does not exist");
        }

        @Override
        public Optional<ExecutionWorkspace> findById(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                ExecutionWorkspaceId workspaceId) {
            return values.stream()
                    .filter(value -> matches(value, organizationId, teamId, workProjectId))
                    .filter(value -> value.id().equals(workspaceId))
                    .findFirst();
        }

        @Override
        public Optional<ExecutionWorkspace> findByTaskExecution(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                TaskExecutionId taskExecutionId) {
            return values.stream()
                    .filter(value -> matches(value, organizationId, teamId, workProjectId))
                    .filter(value -> value.taskExecutionId().equals(taskExecutionId))
                    .findFirst();
        }

        @Override
        public List<ExecutionWorkspace> findRecoveringForUpdate(
                OrganizationId organizationId, RuntimeEnvironment environment, int limit) {
            requireLimit(limit);
            return values.stream()
                    .filter(value -> value.scope().organizationId().equals(organizationId))
                    .filter(value -> value.ownership().environment().equals(environment))
                    .filter(value -> value.status() == ExecutionWorkspaceStatus.RECOVERING)
                    .sorted(Comparator.comparing(value -> value.audit().updatedAt()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ExecutionWorkspace> findRetentionDueForUpdate(
                OrganizationId organizationId, UtcTimestamp authoritativeNow, int limit) {
            requireLimit(limit);
            return values.stream()
                    .filter(value -> value.scope().organizationId().equals(organizationId))
                    .filter(value -> value.status().isRetentionTerminal())
                    .filter(value -> value.retention().isDue(authoritativeNow))
                    .sorted(Comparator.comparing(value -> value.retention().retainUntil()))
                    .limit(limit)
                    .toList();
        }

        private static boolean matches(
                ExecutionWorkspace value,
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId) {
            return value.scope().organizationId().equals(organizationId)
                    && value.scope().teamId().equals(teamId)
                    && value.scope().projectId().equals(workProjectId);
        }

        private static void requireLimit(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("limit must be positive");
            }
        }
    }

    private record Fixture(
            WorkItemScope scope,
            Principal owner,
            CodingTargetSnapshot target,
            TaskExecution execution,
            ExecutionLease prepareLease,
            ExecutionWorkspaceRetention retention) {

        private static Fixture create(UtcTimestamp retainUntil) {
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
                    CREATED_AT);
            WorkItemId workItemId = WorkItemId.generate();
            TaskResponsibilitySnapshot responsibilities = new TaskResponsibilitySnapshot(
                    scope,
                    workItemId,
                    List.of(
                            responsibility(ResponsibilityRole.OWNER, ownerId, PrincipalType.USER),
                            responsibility(
                                    ResponsibilityRole.EXECUTOR,
                                    PrincipalId.generate(),
                                    PrincipalType.SPECIALIST_AGENT)),
                    CREATED_AT);
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
                    new TaskBrief("Implement managed workspace", List.of("Tests pass")),
                    responsibilities,
                    TaskStatus.CREATED,
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    AuditMetadata.createdBy(ownerId, CREATED_AT));
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
                    AuditMetadata.createdBy(ownerId, CREATED_AT));
            CodingTargetSnapshot target = CodingTargetSnapshot.initial(
                    CodingTargetSnapshotId.generate(),
                    task,
                    binding,
                    new RepositoryBranchName("main"),
                    new RepositoryCommitId("a".repeat(40)),
                    CodingTargetAllowedPaths.of("src", "docs"),
                    new BuildProfileReference(
                            "maven", 1, TaskFactHash.sha256("maven-java-17-v1")),
                    owner,
                    CREATED_AT);
            TaskExecution execution = TaskExecution.firstAttempt(
                            TaskExecutionId.generate(),
                            task,
                            3,
                            TaskExecutionPriority.NORMAL,
                            CREATED_AT,
                            owner,
                            CREATED_AT)
                    .markReady(0, owner, READY_AT)
                    .claim(1, owner, CLAIM_AT)
                    .beginPreparing(2, owner, PREPARE_AT);
            ExecutionLease prepareLease = lease(
                    execution,
                    ExecutionLeasePhase.PREPARE,
                    ExecutionLeaseId.generate(),
                    ExecutionRuntimeId.generate(),
                    RuntimeWorkerId.generate(),
                    0);
            return new Fixture(
                    scope,
                    owner,
                    target,
                    execution,
                    prepareLease,
                    new ExecutionWorkspaceRetention(retainUntil));
        }

        private ExecutionWorkspace allocate() {
            return ExecutionWorkspace.allocate(
                    ExecutionWorkspaceId.generate(),
                    target,
                    execution,
                    prepareLease,
                    retention,
                    owner,
                    ALLOCATE_AT);
        }

        private ExecutionWorkspace failed() {
            return allocate().fail(
                    new ExecutionWorkspaceFailure("PROVISION_FAILED"),
                    0,
                    owner,
                    RECOVER_AT);
        }

        private ExecutionWorkspace recovering() {
            ExecutionWorkspace ready = allocate()
                    .beginProvisioning(
                            execution,
                            prepareLease,
                            0,
                            owner,
                            UtcTimestamp.parse("2026-08-17T05:04:10Z"))
                    .markReady(
                            execution,
                            prepareLease,
                            1,
                            owner,
                            UtcTimestamp.parse("2026-08-17T05:04:20Z"));
            TaskExecution running = execution.beginRunning(3, owner, RUN_AT);
            ExecutionLease runLease = lease(
                    running,
                    ExecutionLeasePhase.RUN,
                    prepareLease.id(),
                    prepareLease.runtimeId(),
                    prepareLease.workerId(),
                    1);
            ExecutionWorkspace active = ready.activate(
                    running,
                    runLease,
                    2,
                    owner,
                    UtcTimestamp.parse("2026-08-17T05:05:10Z"));
            TaskExecution recoveringExecution = running.beginRecovery(4, owner, RECOVER_AT);
            return active.beginRecovery(
                    recoveringExecution,
                    3,
                    owner,
                    UtcTimestamp.parse("2026-08-17T05:06:10Z"));
        }

        private static TaskResponsibilitySnapshotEntry responsibility(
                ResponsibilityRole role, PrincipalId principalId, PrincipalType type) {
            return new TaskResponsibilitySnapshotEntry(
                    ResponsibilityAssignmentId.generate(),
                    0,
                    role,
                    principalId,
                    type,
                    type == PrincipalType.USER
                            ? Optional.of(TeamMemberId.generate())
                            : Optional.empty(),
                    CREATED_AT,
                    CREATED_AT);
        }

        private static ExecutionLease lease(
                TaskExecution execution,
                ExecutionLeasePhase phase,
                ExecutionLeaseId leaseId,
                ExecutionRuntimeId runtimeId,
                RuntimeWorkerId workerId,
                long version) {
            return ExecutionLease.reconstitute(
                    leaseId,
                    execution.scope().organizationId(),
                    ENVIRONMENT,
                    execution.id(),
                    execution.attempt(),
                    runtimeId,
                    workerId,
                    new ClaimTokenHash("d".repeat(64)),
                    FencingToken.initial(),
                    phase,
                    PREPARE_AT,
                    phase == ExecutionLeasePhase.PREPARE ? PREPARE_AT : RUN_AT,
                    UtcTimestamp.parse("2026-08-17T06:00:00Z"),
                    Optional.empty(),
                    version);
        }
    }
}
