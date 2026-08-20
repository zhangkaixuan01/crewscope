package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceCompletionReason;
import io.crewscope.domain.coding.ExecutionWorkspaceFailure;
import io.crewscope.domain.coding.ExecutionWorkspaceFingerprint;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.ExecutionWorkspaceRetention;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.ManagedWorkspaceBranch;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingScope;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.coding.WorkspaceArchiveReference;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExecutionWorkspaceTest {

    private static final UtcTimestamp TARGET_AT = UtcTimestamp.parse("2026-08-13T08:10:00Z");
    private static final UtcTimestamp READY_AT = UtcTimestamp.parse("2026-08-13T08:20:00Z");
    private static final UtcTimestamp CLAIM_AT = UtcTimestamp.parse("2026-08-13T08:30:00Z");
    private static final UtcTimestamp PREPARE_AT = UtcTimestamp.parse("2026-08-13T08:40:00Z");
    private static final UtcTimestamp ALLOCATE_AT = UtcTimestamp.parse("2026-08-13T08:45:00Z");
    private static final UtcTimestamp PROVISION_AT = UtcTimestamp.parse("2026-08-13T08:46:00Z");
    private static final UtcTimestamp WORKTREE_READY_AT =
            UtcTimestamp.parse("2026-08-13T08:47:00Z");
    private static final UtcTimestamp RUN_AT = UtcTimestamp.parse("2026-08-13T08:50:00Z");
    private static final UtcTimestamp ACTIVE_AT = UtcTimestamp.parse("2026-08-13T08:51:00Z");
    private static final UtcTimestamp CONTROL_AT = UtcTimestamp.parse("2026-08-13T08:55:00Z");
    private static final UtcTimestamp RESUME_AT = UtcTimestamp.parse("2026-08-13T09:05:00Z");
    private static final UtcTimestamp RETAIN_UNTIL =
            UtcTimestamp.parse("2026-08-13T10:00:00Z");
    private static final UtcTimestamp LEASE_EXPIRES_AT =
            UtcTimestamp.parse("2026-08-13T09:30:00Z");

    @Test
    void allocatesClosedAttemptOwnershipAndStableManagedIdentifiers() {
        Fixture fixture = Fixture.create();
        ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();

        ExecutionWorkspace workspace = fixture.allocate(workspaceId);

        assertEquals(workspaceId, workspace.id());
        assertEquals(fixture.execution.scope(), workspace.scope());
        assertEquals(fixture.task.id(), workspace.taskId());
        assertEquals(fixture.execution.id(), workspace.taskExecutionId());
        assertEquals(1, workspace.attempt());
        assertEquals(fixture.target.reference(), workspace.codingTarget());
        assertEquals(fixture.target.repositoryBindingId(), workspace.repositoryBindingId());
        assertEquals(fixture.target.repositoryBindingVersion(), workspace.repositoryBindingVersion());
        assertEquals(fixture.target.baselineCommit(), workspace.baselineCommit());
        assertEquals("ws-" + workspaceId + "-a1", workspace.workspaceKey().value());
        assertEquals(
                "crewscope/tasks/" + fixture.execution.id() + "/attempt-1",
                workspace.managedBranch().value());
        assertEquals(
                "refs/crewscope/archives/" + workspace.workspaceKey(),
                workspace.archiveReference().value());
        assertEquals(
                "crewscope-java/" + workspace.workspaceKey(),
                workspace.worktreeLocator().relativeValue());
        assertEquals(fixture.prepareLease.id(), workspace.ownership().leaseId());
        assertEquals(fixture.prepareLease.runtimeId(), workspace.ownership().runtimeId());
        assertEquals(fixture.prepareLease.workerId(), workspace.ownership().workerId());
        assertEquals(FencingToken.initial(), workspace.ownership().fencingToken());
        assertEquals(ExecutionWorkspaceStatus.PENDING, workspace.status());
        assertEquals(0, workspace.recoveryGeneration());
        assertEquals(RETAIN_UNTIL, workspace.retention().retainUntil());
        assertEquals(0, workspace.version());
        assertEquals(fixture.owner.id(), workspace.audit().createdBy().orElseThrow());
        assertEquals(
                workspace.fingerprint(),
                reconstitute(workspace, workspace.fingerprint()).fingerprint());
        assertTrue(Arrays.stream(ExecutionWorkspace.class.getDeclaredFields())
                .map(Field::getType)
                .noneMatch(Path.class::isAssignableFrom));
        assertTrue(Arrays.stream(ExecutionWorkspace.class.getMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .noneMatch(Path.class::isAssignableFrom));
        assertTrue(Arrays.stream(ExecutionWorkspace.class.getMethods())
                .map(Method::getReturnType)
                .noneMatch(Path.class::isAssignableFrom));
        ExecutionWorkspaceKey maximumAttemptKey = ExecutionWorkspaceKey.derive(
                ExecutionWorkspaceId.generate(), TaskExecution.MAX_SUPPORTED_ATTEMPTS);
        assertEquals(
                "refs/crewscope/archives/" + maximumAttemptKey,
                WorkspaceArchiveReference.derive(maximumAttemptKey).value());
        assertTrue(ManagedWorkspaceBranch.derive(
                        TaskExecutionId.generate(), TaskExecution.MAX_SUPPORTED_ATTEMPTS)
                .value()
                .endsWith("/attempt-100"));
    }

    @Test
    void rejectsTargetExecutionLeaseActorAndRetentionMismatches() {
        Fixture fixture = Fixture.create();
        TaskExecution otherTaskExecution = TaskExecution.reconstitute(
                fixture.execution.id(),
                fixture.execution.scope(),
                TaskId.generate(),
                fixture.execution.attempt(),
                fixture.execution.maxAttempts(),
                fixture.execution.parentExecutionId(),
                fixture.execution.priority(),
                fixture.execution.notBefore(),
                fixture.execution.status(),
                fixture.execution.waiting(),
                fixture.execution.controlRequest(),
                fixture.execution.terminal(),
                fixture.execution.planningContext(),
                fixture.execution.lastFencingToken(),
                fixture.execution.version(),
                fixture.execution.audit());
        ExecutionLease expiredLease = lease(
                fixture.execution,
                ExecutionLeasePhase.PREPARE,
                ExecutionLeaseId.generate(),
                FencingToken.initial(),
                UtcTimestamp.parse("2026-08-13T08:44:00Z"));
        Principal outsideTeam = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(OrganizationId.generate(), TeamId.generate()),
                PrincipalType.USER,
                Optional.empty(),
                "Outside team",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                TaskDomainFixture.CREATED_AT);

        assertThrows(
                DomainValidationException.class,
                () -> ExecutionWorkspace.allocate(
                        ExecutionWorkspaceId.generate(),
                        fixture.target,
                        otherTaskExecution,
                        fixture.prepareLease,
                        fixture.retention,
                        fixture.owner,
                        ALLOCATE_AT));
        assertThrows(
                DomainValidationException.class,
                () -> ExecutionWorkspace.allocate(
                        ExecutionWorkspaceId.generate(),
                        fixture.target,
                        fixture.execution,
                        expiredLease,
                        fixture.retention,
                        fixture.owner,
                        ALLOCATE_AT));
        assertThrows(
                DomainValidationException.class,
                () -> ExecutionWorkspace.allocate(
                        ExecutionWorkspaceId.generate(),
                        fixture.target,
                        fixture.execution,
                        fixture.prepareLease,
                        new ExecutionWorkspaceRetention(ALLOCATE_AT),
                        fixture.owner,
                        ALLOCATE_AT));
        assertThrows(
                DomainValidationException.class,
                () -> ExecutionWorkspace.allocate(
                        ExecutionWorkspaceId.generate(),
                        fixture.target,
                        fixture.execution,
                        fixture.prepareLease,
                        fixture.retention,
                        outsideTeam,
                        ALLOCATE_AT));
    }

    @Test
    void completesTheHappyLifecycleAndArchivesOnlyAfterRetention() {
        Fixture fixture = Fixture.create();
        ExecutionWorkspace workspace = fixture.readyWorkspace();
        Running running = fixture.running();

        ExecutionWorkspace active = workspace.activate(
                running.execution, running.lease, 2, fixture.owner, ACTIVE_AT);
        ExecutionWorkspace finalizing = active.beginFinalizing(
                ExecutionWorkspaceCompletionReason.SUCCEEDED,
                running.execution,
                3,
                fixture.owner,
                CONTROL_AT);
        TaskExecution completedExecution = running.execution.complete(
                4, fixture.owner, UtcTimestamp.parse("2026-08-13T09:00:00Z"));
        ExecutionWorkspace completed = finalizing.completeFinalizing(
                completedExecution,
                4,
                fixture.owner,
                UtcTimestamp.parse("2026-08-13T09:01:00Z"));

        assertEquals(ExecutionWorkspaceStatus.ACTIVE, active.status());
        assertEquals(ExecutionWorkspaceStatus.FINALIZING, finalizing.status());
        assertEquals(
                Optional.of(ExecutionWorkspaceCompletionReason.SUCCEEDED),
                finalizing.completionReason());
        assertEquals(ExecutionWorkspaceStatus.COMPLETED, completed.status());
        assertEquals(5, completed.version());
        assertEquals(active.fingerprint(), completed.fingerprint());
        assertThrows(
                DomainValidationException.class,
                () -> completed.archive(
                        UtcTimestamp.parse("2026-08-13T09:59:59Z"),
                        5,
                        fixture.owner));

        ExecutionWorkspace archived = completed.archive(RETAIN_UNTIL, 5, fixture.owner);

        assertEquals(ExecutionWorkspaceStatus.ARCHIVED, archived.status());
        assertEquals(6, archived.version());
        assertEquals(completed.workspaceKey(), archived.workspaceKey());
        assertEquals(completed.fingerprint(), archived.fingerprint());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.fail(
                        new ExecutionWorkspaceFailure("LATE_FAILURE"),
                        6,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-13T10:01:00Z")));
    }

    @Test
    void rejectsIllegalTransitionsAndStaleAggregateVersions() {
        Fixture fixture = Fixture.create();
        ExecutionWorkspace pending = fixture.allocate(ExecutionWorkspaceId.generate());
        Running running = fixture.running();

        assertThrows(
                OptimisticLockConflictException.class,
                () -> pending.beginProvisioning(
                        fixture.execution,
                        fixture.prepareLease,
                        1,
                        fixture.owner,
                        PROVISION_AT));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> pending.activate(
                        running.execution,
                        running.lease,
                        0,
                        fixture.owner,
                        ACTIVE_AT));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> pending.completeFinalizing(
                        running.execution,
                        0,
                        fixture.owner,
                        CONTROL_AT));
    }

    @Test
    void pausePreservesTheWorktreeAndResumeRequiresANewerOwnershipEpoch() {
        Fixture fixture = Fixture.create();
        Running running = fixture.running();
        ExecutionWorkspace active = fixture.readyWorkspace().activate(
                running.execution, running.lease, 2, fixture.owner, ACTIVE_AT);
        TaskExecution pausedExecution = running.execution
                .requestPause("Member requested pause", 4, fixture.owner, CONTROL_AT)
                .acknowledgePaused(
                        5,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-13T08:56:00Z"));

        ExecutionWorkspace paused = active.preserveForPause(
                pausedExecution,
                3,
                fixture.owner,
                UtcTimestamp.parse("2026-08-13T08:57:00Z"));

        assertEquals(ExecutionWorkspaceStatus.READY, paused.status());
        assertEquals(active.workspaceKey(), paused.workspaceKey());
        assertEquals(active.managedBranch(), paused.managedBranch());
        assertEquals(active.archiveReference(), paused.archiveReference());
        assertEquals(active.fingerprint(), paused.fingerprint());

        TaskExecution resumedPreparing = pausedExecution
                .requeue(RESUME_AT, 6, fixture.owner, RESUME_AT)
                .claim(
                        7,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-13T09:06:00Z"))
                .beginPreparing(
                        8,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-13T09:07:00Z"));
        ExecutionLease resumedPrepareLease = lease(
                resumedPreparing,
                ExecutionLeasePhase.PREPARE,
                ExecutionLeaseId.generate(),
                new FencingToken(2),
                LEASE_EXPIRES_AT);

        assertThrows(
                DomainValidationException.class,
                () -> paused.rebindForResume(
                        resumedPreparing,
                        fixture.prepareLease,
                        4,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-13T09:08:00Z")));

        ExecutionWorkspace rebound = paused.rebindForResume(
                resumedPreparing,
                resumedPrepareLease,
                4,
                fixture.owner,
                UtcTimestamp.parse("2026-08-13T09:08:00Z"));

        assertEquals(ExecutionWorkspaceStatus.READY, rebound.status());
        assertEquals(new FencingToken(2), rebound.ownership().fencingToken());
        assertNotEquals(paused.fingerprint(), rebound.fingerprint());
        assertEquals(paused.workspaceKey(), rebound.workspaceKey());
    }

    @Test
    void cancellationFinalizesEvidenceInsteadOfDeletingWorkspaceFacts() {
        Fixture fixture = Fixture.create();
        Running running = fixture.running();
        ExecutionWorkspace active = fixture.readyWorkspace().activate(
                running.execution, running.lease, 2, fixture.owner, ACTIVE_AT);
        TaskExecution cancelRequested = running.execution.requestCancel(
                "Stop this work", 4, fixture.owner, CONTROL_AT);

        ExecutionWorkspace finalizing = active.beginFinalizing(
                ExecutionWorkspaceCompletionReason.CANCELLED,
                cancelRequested,
                3,
                fixture.owner,
                UtcTimestamp.parse("2026-08-13T08:56:00Z"));
        TaskExecution cancelled = cancelRequested.acknowledgeCancelled(
                5,
                fixture.owner,
                UtcTimestamp.parse("2026-08-13T08:57:00Z"));
        ExecutionWorkspace completed = finalizing.completeFinalizing(
                cancelled,
                4,
                fixture.owner,
                UtcTimestamp.parse("2026-08-13T08:58:00Z"));

        assertEquals(ExecutionWorkspaceStatus.COMPLETED, completed.status());
        assertEquals(
                Optional.of(ExecutionWorkspaceCompletionReason.CANCELLED),
                completed.completionReason());
        assertEquals(active.workspaceKey(), completed.workspaceKey());
        assertEquals(active.baselineCommit(), completed.baselineCommit());
        assertEquals(active.fingerprint(), completed.fingerprint());
    }

    @Test
    void committedCancellationSupersedesAnAlreadySealedSuccessfulResult() {
        Fixture fixture = Fixture.create();
        Running running = fixture.running();
        ExecutionWorkspace active = fixture.readyWorkspace().activate(
                running.execution, running.lease, 2, fixture.owner, ACTIVE_AT);
        ExecutionWorkspace sealed = active.beginFinalizing(
                ExecutionWorkspaceCompletionReason.SUCCEEDED,
                running.execution,
                3,
                fixture.owner,
                CONTROL_AT);
        TaskExecution cancelled = running.execution
                .requestCancel("Cancel after final verification", 4, fixture.owner, CONTROL_AT)
                .acknowledgeCancelled(
                        5,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-13T08:57:00Z"));

        ExecutionWorkspace completed = sealed.completeFinalizing(
                cancelled,
                4,
                fixture.owner,
                UtcTimestamp.parse("2026-08-13T08:58:00Z"));

        assertEquals(ExecutionWorkspaceStatus.COMPLETED, completed.status());
        assertEquals(
                Optional.of(ExecutionWorkspaceCompletionReason.CANCELLED),
                completed.completionReason());
        assertEquals(sealed.fingerprint(), completed.fingerprint());
    }

    @Test
    void recoveryRestoresTheInterruptedStateWithANewGenerationAndLease() {
        Fixture fixture = Fixture.create();
        Running running = fixture.running();
        ExecutionWorkspace active = fixture.readyWorkspace().activate(
                running.execution, running.lease, 2, fixture.owner, ACTIVE_AT);
        TaskExecution recoveringExecution = running.execution.beginRecovery(
                4, fixture.owner, CONTROL_AT);

        ExecutionWorkspace recovering = active.beginRecovery(
                recoveringExecution,
                3,
                fixture.owner,
                UtcTimestamp.parse("2026-08-13T08:56:00Z"));

        assertEquals(ExecutionWorkspaceStatus.RECOVERING, recovering.status());
        assertEquals(Optional.of(ExecutionWorkspaceStatus.ACTIVE), recovering.recoveryTargetStatus());
        assertEquals(1, recovering.recoveryGeneration());
        assertNotEquals(active.fingerprint(), recovering.fingerprint());

        TaskExecution preparingAgain = recoveringExecution
                .requeue(RESUME_AT, 5, fixture.owner, RESUME_AT)
                .claim(
                        6,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-13T09:06:00Z"))
                .beginPreparing(
                        7,
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-13T09:07:00Z"));
        ExecutionLease replacementLease = lease(
                preparingAgain,
                ExecutionLeasePhase.PREPARE,
                ExecutionLeaseId.generate(),
                new FencingToken(2),
                LEASE_EXPIRES_AT);
        ExecutionWorkspace recovered = recovering.resumeRecovery(
                preparingAgain,
                replacementLease,
                4,
                fixture.owner,
                UtcTimestamp.parse("2026-08-13T09:08:00Z"));

        assertEquals(ExecutionWorkspaceStatus.ACTIVE, recovered.status());
        assertEquals(Optional.empty(), recovered.recoveryTargetStatus());
        assertEquals(1, recovered.recoveryGeneration());
        assertEquals(new FencingToken(2), recovered.ownership().fencingToken());
        assertEquals(active.workspaceKey(), recovered.workspaceKey());
        assertNotEquals(recovering.fingerprint(), recovered.fingerprint());
    }

    @Test
    void retryAttemptAlwaysReceivesAnIsolatedWorkspaceBranchAndIdentity() {
        Fixture fixture = Fixture.create();
        ExecutionWorkspace first = fixture.allocate(ExecutionWorkspaceId.generate());
        TaskExecution retryExecution = TaskExecution.reconstitute(
                TaskExecutionId.generate(),
                fixture.execution.scope(),
                fixture.execution.taskId(),
                2,
                fixture.execution.maxAttempts(),
                Optional.of(fixture.execution.id()),
                fixture.execution.priority(),
                fixture.execution.notBefore(),
                TaskExecutionStatus.PREPARING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(FencingToken.initial()),
                0,
                AuditMetadata.createdBy(fixture.owner.id(), TaskDomainFixture.CREATED_AT));
        ExecutionLease retryLease = lease(
                retryExecution,
                ExecutionLeasePhase.PREPARE,
                ExecutionLeaseId.generate(),
                FencingToken.initial(),
                LEASE_EXPIRES_AT);
        ExecutionWorkspace retry = ExecutionWorkspace.allocate(
                ExecutionWorkspaceId.generate(),
                fixture.target,
                retryExecution,
                retryLease,
                fixture.retention,
                fixture.owner,
                ALLOCATE_AT);

        assertEquals(1, first.attempt());
        assertEquals(2, retry.attempt());
        assertNotEquals(first.id(), retry.id());
        assertNotEquals(first.workspaceKey(), retry.workspaceKey());
        assertNotEquals(first.managedBranch(), retry.managedBranch());
        assertNotEquals(first.worktreeLocator(), retry.worktreeLocator());
        assertTrue(retry.managedBranch().value().endsWith("/attempt-2"));
    }

    @Test
    void failureIsStableAndCanOnlyAdvanceToArchivedAfterRetention() {
        Fixture fixture = Fixture.create();
        ExecutionWorkspace failed = fixture.allocate(ExecutionWorkspaceId.generate()).fail(
                new ExecutionWorkspaceFailure("UNOWNED_PATH_RESIDUE"),
                0,
                fixture.owner,
                PROVISION_AT);

        assertEquals(ExecutionWorkspaceStatus.FAILED, failed.status());
        assertEquals("UNOWNED_PATH_RESIDUE", failed.failure().orElseThrow().code());
        assertFalse(failed.completionReason().isPresent());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> failed.beginProvisioning(
                        fixture.execution,
                        fixture.prepareLease,
                        1,
                        fixture.owner,
                        WORKTREE_READY_AT));
        assertThrows(
                DomainValidationException.class,
                () -> failed.archive(CONTROL_AT, 1, fixture.owner));
        assertEquals(
                ExecutionWorkspaceStatus.ARCHIVED,
                failed.archive(RETAIN_UNTIL, 1, fixture.owner).status());
    }

    @Test
    void reconstitutionRejectsFingerprintAndRecoveryShapeTampering() {
        Fixture fixture = Fixture.create();
        ExecutionWorkspace workspace = fixture.allocate(ExecutionWorkspaceId.generate());

        assertThrows(
                DomainValidationException.class,
                () -> reconstitute(
                        workspace,
                        new ExecutionWorkspaceFingerprint("f".repeat(64))));
        assertThrows(
                DomainValidationException.class,
                () -> ExecutionWorkspace.reconstitute(
                        workspace.id(),
                        workspace.scope(),
                        workspace.taskId(),
                        workspace.taskExecutionId(),
                        workspace.attempt(),
                        workspace.codingTarget(),
                        workspace.repositoryBindingId(),
                        workspace.repositoryBindingVersion(),
                        workspace.repositoryKey(),
                        workspace.baselineCommit(),
                        workspace.workspaceKey(),
                        workspace.managedBranch(),
                        workspace.archiveReference(),
                        workspace.ownership(),
                        ExecutionWorkspaceStatus.RECOVERING,
                        Optional.empty(),
                        1,
                        Optional.empty(),
                        Optional.empty(),
                        workspace.retention(),
                        workspace.fingerprint(),
                        workspace.version(),
                        workspace.audit()));
        assertThrows(
                DomainValidationException.class,
                () -> ExecutionWorkspace.reconstitute(
                        workspace.id(),
                        workspace.scope(),
                        workspace.taskId(),
                        workspace.taskExecutionId(),
                        workspace.attempt(),
                        workspace.codingTarget(),
                        workspace.repositoryBindingId(),
                        workspace.repositoryBindingVersion(),
                        workspace.repositoryKey(),
                        workspace.baselineCommit(),
                        workspace.workspaceKey(),
                        new ManagedWorkspaceBranch(
                                "crewscope/tasks/"
                                        + workspace.taskExecutionId()
                                        + "/attempt-2"),
                        workspace.archiveReference(),
                        workspace.ownership(),
                        workspace.status(),
                        workspace.recoveryTargetStatus(),
                        workspace.recoveryGeneration(),
                        workspace.completionReason(),
                        workspace.failure(),
                        workspace.retention(),
                        workspace.fingerprint(),
                        workspace.version(),
                        workspace.audit()));
    }

    private static ExecutionWorkspace reconstitute(
            ExecutionWorkspace workspace, ExecutionWorkspaceFingerprint expectedFingerprint) {
        return ExecutionWorkspace.reconstitute(
                workspace.id(),
                workspace.scope(),
                workspace.taskId(),
                workspace.taskExecutionId(),
                workspace.attempt(),
                workspace.codingTarget(),
                workspace.repositoryBindingId(),
                workspace.repositoryBindingVersion(),
                workspace.repositoryKey(),
                workspace.baselineCommit(),
                workspace.workspaceKey(),
                workspace.managedBranch(),
                workspace.archiveReference(),
                workspace.ownership(),
                workspace.status(),
                workspace.recoveryTargetStatus(),
                workspace.recoveryGeneration(),
                workspace.completionReason(),
                workspace.failure(),
                workspace.retention(),
                expectedFingerprint,
                workspace.version(),
                workspace.audit());
    }

    private static ExecutionLease lease(
            TaskExecution execution,
            ExecutionLeasePhase phase,
            ExecutionLeaseId leaseId,
            FencingToken fencingToken,
            UtcTimestamp expiresAt) {
        return ExecutionLease.reconstitute(
                leaseId,
                execution.scope().organizationId(),
                new RuntimeEnvironment("test"),
                execution.id(),
                execution.attempt(),
                ExecutionRuntimeId.generate(),
                RuntimeWorkerId.generate(),
                new ClaimTokenHash("d".repeat(64)),
                fencingToken,
                phase,
                PREPARE_AT,
                PREPARE_AT,
                expiresAt,
                Optional.empty(),
                0);
    }

    private record Running(TaskExecution execution, ExecutionLease lease) {}

    private record Fixture(
            TaskDomainFixture domain,
            io.crewscope.domain.identity.Principal owner,
            Task task,
            CodingTargetSnapshot target,
            TaskExecution execution,
            ExecutionLease prepareLease,
            ExecutionWorkspaceRetention retention) {

        private static Fixture create() {
            TaskDomainFixture domain = new TaskDomainFixture();
            Task task = Task.create(
                    TaskId.generate(),
                    domain.workItem,
                    TaskSource.fromWorkItem(domain.workItem),
                    new TaskBrief(
                            "Implement managed Coding workspace",
                            List.of("Worktree lifecycle passes", "Recovery remains isolated")),
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
                    CodingTargetAllowedPaths.of("crewscope-domain", "docs"),
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
            ExecutionLease prepareLease = lease(
                    execution,
                    ExecutionLeasePhase.PREPARE,
                    ExecutionLeaseId.generate(),
                    FencingToken.initial(),
                    LEASE_EXPIRES_AT);
            return new Fixture(
                    domain,
                    domain.owner,
                    task,
                    target,
                    execution,
                    prepareLease,
                    new ExecutionWorkspaceRetention(RETAIN_UNTIL));
        }

        private ExecutionWorkspace allocate(ExecutionWorkspaceId id) {
            return ExecutionWorkspace.allocate(
                    id, target, execution, prepareLease, retention, owner, ALLOCATE_AT);
        }

        private ExecutionWorkspace readyWorkspace() {
            return allocate(ExecutionWorkspaceId.generate())
                    .beginProvisioning(
                            execution, prepareLease, 0, owner, PROVISION_AT)
                    .markReady(
                            execution, prepareLease, 1, owner, WORKTREE_READY_AT);
        }

        private Running running() {
            TaskExecution runningExecution = execution.beginRunning(3, owner, RUN_AT);
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
            return new Running(runningExecution, runLease);
        }
    }
}
