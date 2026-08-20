package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskExecutionTest {

    private static final UtcTimestamp READY_AT =
            UtcTimestamp.parse("2026-08-13T08:05:00Z");
    private static final UtcTimestamp CLAIM_AT =
            UtcTimestamp.parse("2026-08-13T08:10:00Z");
    private static final UtcTimestamp PREPARE_AT =
            UtcTimestamp.parse("2026-08-13T08:15:00Z");
    private static final UtcTimestamp RUN_AT =
            UtcTimestamp.parse("2026-08-13T08:20:00Z");
    private static final UtcTimestamp FINISH_AT =
            UtcTimestamp.parse("2026-08-13T08:30:00Z");

    @Test
    void createsFirstAttemptWithImmutableScopeScheduleAndRetryPolicy() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        TaskExecution execution = first(fixture, 3);

        assertEquals(fixture.task().scope(), execution.scope());
        assertEquals(1, execution.attempt());
        assertEquals(3, execution.maxAttempts());
        assertTrue(execution.parentExecutionId().isEmpty());
        assertEquals(TaskExecutionPriority.NORMAL, execution.priority());
        assertEquals(TaskExecutionStatus.CREATED, execution.status());
        assertEquals(0, execution.version());
        assertFalse(execution.isTerminal());
    }

    @Test
    void runsHappyPathFromReadyThroughCompleted() {
        TaskDomainFixture fixture = new TaskDomainFixture();

        TaskExecution completed = first(fixture, 3)
                .markReady(0, fixture.owner, READY_AT)
                .claim(1, fixture.executor, CLAIM_AT)
                .beginPreparing(2, fixture.executor, PREPARE_AT)
                .beginRunning(3, fixture.executor, RUN_AT)
                .complete(4, fixture.executor, FINISH_AT);

        assertEquals(TaskExecutionStatus.COMPLETED, completed.status());
        assertEquals(FencingToken.initial(), completed.lastFencingToken().orElseThrow());
        assertEquals(5, completed.version());
        assertTrue(completed.isTerminal());
        assertEquals(
                TaskExecutionStatus.COMPLETED,
                completed.terminal().orElseThrow().status());
        assertTrue(completed.waiting().isEmpty());
        assertTrue(completed.controlRequest().isEmpty());
    }

    @Test
    void advancesFencingEpochOnEveryReclaimAndPreservesItAcrossRecovery() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        TaskExecution claimed = first(fixture, 3)
                .markReady(0, fixture.owner, READY_AT)
                .claim(1, fixture.executor, CLAIM_AT);
        TaskExecution requeued = claimed
                .beginRecovery(2, fixture.owner, PREPARE_AT)
                .requeue(RUN_AT, 3, fixture.owner, RUN_AT);
        TaskExecution reclaimed = requeued.claim(4, fixture.executor, RUN_AT);

        assertEquals(FencingToken.initial(), claimed.lastFencingToken().orElseThrow());
        assertEquals(claimed.lastFencingToken(), requeued.lastFencingToken());
        assertEquals(FencingToken.initial().next(), reclaimed.lastFencingToken().orElseThrow());
    }

    @Test
    void reconstitutionRequiresFencingForOwnedStatesAndPreservesHistoryWhileReady() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        TaskExecution claimed = first(fixture, 3)
                .markReady(0, fixture.owner, READY_AT)
                .claim(1, fixture.executor, CLAIM_AT);
        TaskExecution readyAgain = claimed
                .beginRecovery(2, fixture.owner, PREPARE_AT)
                .requeue(RUN_AT, 3, fixture.owner, RUN_AT);

        assertThrows(
                DomainValidationException.class,
                () -> TaskExecution.reconstitute(
                        claimed.id(), claimed.scope(), claimed.taskId(), claimed.attempt(),
                        claimed.maxAttempts(), claimed.parentExecutionId(), claimed.priority(),
                        claimed.notBefore(), claimed.status(), claimed.waiting(),
                        claimed.controlRequest(), claimed.terminal(), claimed.planningContext(),
                        Optional.empty(), claimed.version(), claimed.audit()));

        TaskExecution restored = TaskExecution.reconstitute(
                readyAgain.id(), readyAgain.scope(), readyAgain.taskId(), readyAgain.attempt(),
                readyAgain.maxAttempts(), readyAgain.parentExecutionId(), readyAgain.priority(),
                readyAgain.notBefore(), readyAgain.status(), readyAgain.waiting(),
                readyAgain.controlRequest(), readyAgain.terminal(), readyAgain.planningContext(),
                readyAgain.lastFencingToken(), readyAgain.version(), readyAgain.audit());

        assertEquals(TaskExecutionStatus.READY, restored.status());
        assertEquals(FencingToken.initial(), restored.lastFencingToken().orElseThrow());
    }

    @Test
    void separatesRuntimeWaitingFromLifecycleAndRequeuesWithSchedule() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        UtcTimestamp delayed = UtcTimestamp.parse("2026-08-13T09:30:00Z");

        TaskExecution waiting = first(fixture, 3)
                .markReady(0, fixture.owner, READY_AT)
                .waitForRuntime(1, fixture.owner, CLAIM_AT);
        TaskExecution requeued = waiting.requeue(delayed, 2, fixture.owner, RUN_AT);

        assertEquals(TaskExecutionStatus.WAITING, waiting.status());
        assertEquals(
                TaskExecutionWaitReason.RUNTIME,
                waiting.waiting().orElseThrow().reason());
        assertEquals(TaskExecutionStatus.READY, requeued.status());
        assertTrue(requeued.waiting().isEmpty());
        assertEquals(delayed, requeued.notBefore());
    }

    @Test
    void recordsExplicitRunningWaitReasonAndRejectsRuntimeShortcut() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        TaskExecution running = running(fixture, 3);

        TaskExecution waiting = running.waitFor(
                TaskExecutionWaitReason.USER_INPUT,
                4,
                fixture.executor,
                FINISH_AT);

        assertEquals(TaskExecutionStatus.WAITING, waiting.status());
        assertEquals(
                TaskExecutionWaitReason.USER_INPUT,
                waiting.waiting().orElseThrow().reason());
        assertThrows(
                DomainValidationException.class,
                () -> running.waitFor(
                        TaskExecutionWaitReason.RUNTIME,
                        4,
                        fixture.executor,
                        FINISH_AT));
    }

    @Test
    void pausesAtSafePointAndResumesThroughReadyQueue() {
        TaskDomainFixture fixture = new TaskDomainFixture();

        TaskExecution requested = running(fixture, 3)
                .requestPause("  User needs to inspect progress  ", 4, fixture.owner, FINISH_AT);
        TaskExecution paused = requested.acknowledgePaused(
                5, fixture.executor, UtcTimestamp.parse("2026-08-13T08:31:00Z"));
        TaskExecution resumed = paused.requeue(
                UtcTimestamp.parse("2026-08-13T08:32:00Z"),
                6,
                fixture.owner,
                UtcTimestamp.parse("2026-08-13T08:32:00Z"));

        assertEquals(TaskExecutionStatus.PAUSE_REQUESTED, requested.status());
        assertEquals(
                "User needs to inspect progress",
                requested.controlRequest().orElseThrow().reason());
        assertEquals(TaskExecutionStatus.PAUSED, paused.status());
        assertEquals(TaskExecutionStatus.READY, resumed.status());
        assertTrue(resumed.controlRequest().isEmpty());
    }

    @Test
    void completedSafePointWinsAPauseThatArrivesAfterResultSealing() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        TaskExecution requested = running(fixture, 3)
                .requestPause("Review the completed change", 4, fixture.owner, FINISH_AT);

        TaskExecution completed = requested.complete(
                5,
                fixture.executor,
                UtcTimestamp.parse("2026-08-13T08:31:00Z"));

        assertEquals(TaskExecutionStatus.COMPLETED, completed.status());
        assertTrue(completed.controlRequest().isEmpty());
        assertEquals(
                TaskExecutionStatus.COMPLETED,
                completed.terminal().orElseThrow().status());
    }

    @Test
    void cancelsOnlyAfterRequestAndPreservesDecisionFacts() {
        TaskDomainFixture fixture = new TaskDomainFixture();

        TaskExecution requested = running(fixture, 3)
                .requestCancel("Superseded", 4, fixture.owner, FINISH_AT);
        TaskExecution cancelled = requested.acknowledgeCancelled(
                5,
                fixture.executor,
                UtcTimestamp.parse("2026-08-13T08:31:00Z"));

        assertEquals(TaskExecutionStatus.CANCELLED, cancelled.status());
        assertEquals(
                TaskExecutionControlRequestType.CANCEL,
                cancelled.controlRequest().orElseThrow().type());
        assertEquals(
                fixture.executor.id(),
                cancelled.terminal().orElseThrow().decidedByPrincipalId());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> cancelled.markReady(6, fixture.owner, FINISH_AT));
    }

    @Test
    void recoversClaimedPreparingAndRunningAttemptsBeforeRequeue() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        TaskExecution claimed = first(fixture, 3)
                .markReady(0, fixture.owner, READY_AT)
                .claim(1, fixture.executor, CLAIM_AT);
        TaskExecution preparing = claimed.beginPreparing(2, fixture.executor, PREPARE_AT);
        TaskExecution running = preparing.beginRunning(3, fixture.executor, RUN_AT);

        assertEquals(
                TaskExecutionStatus.RECOVERING,
                claimed.beginRecovery(2, fixture.owner, RUN_AT).status());
        assertEquals(
                TaskExecutionStatus.RECOVERING,
                preparing.beginRecovery(3, fixture.owner, RUN_AT).status());
        TaskExecution recovered = running.beginRecovery(4, fixture.owner, FINISH_AT);
        TaskExecution requeued = recovered.requeue(
                UtcTimestamp.parse("2026-08-13T08:31:00Z"),
                5,
                fixture.owner,
                UtcTimestamp.parse("2026-08-13T08:31:00Z"));

        assertEquals(TaskExecutionStatus.READY, requeued.status());
    }

    @Test
    void createsLinearRetryOnlyForCurrentRetryableFailure() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        Task task = fixture.task();
        TaskExecution failed = first(task, fixture, 3)
                .markReady(0, fixture.owner, READY_AT)
                .claim(1, fixture.executor, CLAIM_AT)
                .beginPreparing(2, fixture.executor, PREPARE_AT)
                .beginRunning(3, fixture.executor, RUN_AT)
                .fail(
                        new TaskExecutionFailure(
                                TaskExecutionFailureClass.TRANSIENT, "PROVIDER_TEMPORARY"),
                        4,
                        fixture.executor,
                        FINISH_AT);
        Task failedTask = task
                .switchCurrentExecution(
                        Optional.empty(), failed.id(), 0, fixture.owner, READY_AT)
                .synchronizeStatus(
                        failed.id(), TaskStatus.FAILED, 1, fixture.owner, FINISH_AT);

        TaskExecution retry = TaskExecution.retry(
                TaskExecutionId.generate(),
                failedTask,
                failed,
                new TaskExecutionPriority(70),
                UtcTimestamp.parse("2026-08-13T08:40:00Z"),
                fixture.owner,
                UtcTimestamp.parse("2026-08-13T08:35:00Z"));

        assertTrue(failed.canRetry());
        assertEquals(2, retry.attempt());
        assertEquals(3, retry.maxAttempts());
        assertEquals(Optional.of(failed.id()), retry.parentExecutionId());
        assertEquals(70, retry.priority().value());
        assertEquals(TaskExecutionStatus.CREATED, retry.status());
    }

    @Test
    void rejectsRetryForPermanentFailureOrExhaustedBudget() {
        TaskDomainFixture fixture = new TaskDomainFixture();

        FailedAttempt permanent = failedAttempt(
                fixture, 3, TaskExecutionFailureClass.POLICY_VIOLATION);
        FailedAttempt exhausted = failedAttempt(
                fixture, 1, TaskExecutionFailureClass.TRANSIENT);

        assertFalse(permanent.execution.canRetry());
        assertFalse(exhausted.execution.canRetry());
        assertThrows(
                DomainValidationException.class,
                () -> retry(permanent, fixture));
        assertThrows(
                DomainValidationException.class,
                () -> retry(exhausted, fixture));
    }

    @Test
    void preventsRetryBranchingFromNonCurrentFailedAttempt() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        FailedAttempt failed = failedAttempt(
                fixture, 3, TaskExecutionFailureClass.TRANSIENT);
        Task movedToDifferentAttempt = failed.task.switchCurrentExecution(
                Optional.of(failed.execution.id()),
                TaskExecutionId.generate(),
                failed.task.version(),
                fixture.owner,
                UtcTimestamp.parse("2026-08-13T08:35:00Z"));

        assertThrows(
                DomainValidationException.class,
                () -> TaskExecution.retry(
                        TaskExecutionId.generate(),
                        movedToDifferentAttempt,
                        failed.execution,
                        TaskExecutionPriority.NORMAL,
                        UtcTimestamp.parse("2026-08-13T08:40:00Z"),
                        fixture.owner,
                        UtcTimestamp.parse("2026-08-13T08:35:00Z")));
    }

    @Test
    void rejectsClaimBeforeNotBeforeAndProtectsEveryMutationByVersion() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        UtcTimestamp future = UtcTimestamp.parse("2026-08-13T09:00:00Z");
        TaskExecution ready = TaskExecution.firstAttempt(
                        TaskExecutionId.generate(),
                        fixture.task(),
                        3,
                        TaskExecutionPriority.NORMAL,
                        future,
                        fixture.owner,
                        TaskDomainFixture.CREATED_AT)
                .markReady(0, fixture.owner, READY_AT);

        assertThrows(
                DomainValidationException.class,
                () -> ready.claim(1, fixture.executor, CLAIM_AT));
        assertThrows(
                OptimisticLockConflictException.class,
                () -> ready.reschedule(
                        new TaskExecutionPriority(80), future, 0, fixture.owner, CLAIM_AT));
    }

    @Test
    void rejectsIllegalRollbackAndTerminalMutation() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        TaskExecution completed = running(fixture, 3)
                .complete(4, fixture.executor, FINISH_AT);

        assertThrows(
                InvalidStateTransitionException.class,
                () -> first(fixture, 3).beginRunning(
                        0, fixture.executor, READY_AT));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> completed.requestCancel(
                        "Late request", 5, fixture.owner, FINISH_AT));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> completed.fail(
                        new TaskExecutionFailure(
                                TaskExecutionFailureClass.INTERNAL, "LATE_FAILURE"),
                        5,
                        fixture.executor,
                        FINISH_AT));
    }

    @Test
    void rejectsInvalidReconstitutedStateShapes() {
        TaskDomainFixture fixture = new TaskDomainFixture();
        TaskExecution execution = first(fixture, 3);

        assertThrows(
                DomainValidationException.class,
                () -> TaskExecution.reconstitute(
                        execution.id(),
                        execution.scope(),
                        execution.taskId(),
                        1,
                        3,
                        Optional.empty(),
                        execution.priority(),
                        execution.notBefore(),
                        TaskExecutionStatus.WAITING,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        0,
                        execution.audit()));
        assertThrows(
                DomainValidationException.class,
                () -> TaskExecution.reconstitute(
                        execution.id(),
                        execution.scope(),
                        execution.taskId(),
                        2,
                        3,
                        Optional.empty(),
                        execution.priority(),
                        execution.notBefore(),
                        TaskExecutionStatus.CREATED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        0,
                        execution.audit()));
    }

    @Test
    void validatesPriorityAttemptBudgetAndSafeFailureCode() {
        assertThrows(DomainValidationException.class, () -> new TaskExecutionPriority(-1));
        assertThrows(DomainValidationException.class, () -> new TaskExecutionPriority(101));
        assertThrows(
                DomainValidationException.class,
                () -> new TaskExecutionFailure(
                        TaskExecutionFailureClass.INTERNAL, "raw provider message"));

        TaskDomainFixture fixture = new TaskDomainFixture();
        assertThrows(
                DomainValidationException.class,
                () -> TaskExecution.firstAttempt(
                        TaskExecutionId.generate(),
                        fixture.task(),
                        0,
                        TaskExecutionPriority.NORMAL,
                        TaskDomainFixture.CREATED_AT,
                        fixture.owner,
                        TaskDomainFixture.CREATED_AT));
    }

    private static TaskExecution first(TaskDomainFixture fixture, int maxAttempts) {
        return first(fixture.task(), fixture, maxAttempts);
    }

    private static TaskExecution first(
            Task task, TaskDomainFixture fixture, int maxAttempts) {
        return TaskExecution.firstAttempt(
                TaskExecutionId.generate(),
                task,
                maxAttempts,
                TaskExecutionPriority.NORMAL,
                TaskDomainFixture.CREATED_AT,
                fixture.owner,
                TaskDomainFixture.CREATED_AT);
    }

    private static TaskExecution running(TaskDomainFixture fixture, int maxAttempts) {
        return first(fixture, maxAttempts)
                .markReady(0, fixture.owner, READY_AT)
                .claim(1, fixture.executor, CLAIM_AT)
                .beginPreparing(2, fixture.executor, PREPARE_AT)
                .beginRunning(3, fixture.executor, RUN_AT);
    }

    private static FailedAttempt failedAttempt(
            TaskDomainFixture fixture,
            int maxAttempts,
            TaskExecutionFailureClass failureClass) {
        Task task = fixture.task();
        TaskExecution failed = first(task, fixture, maxAttempts)
                .markReady(0, fixture.owner, READY_AT)
                .claim(1, fixture.executor, CLAIM_AT)
                .beginPreparing(2, fixture.executor, PREPARE_AT)
                .beginRunning(3, fixture.executor, RUN_AT)
                .fail(
                        new TaskExecutionFailure(failureClass, "ATTEMPT_FAILED"),
                        4,
                        fixture.executor,
                        FINISH_AT);
        Task failedTask = task
                .switchCurrentExecution(
                        Optional.empty(), failed.id(), 0, fixture.owner, READY_AT)
                .synchronizeStatus(
                        failed.id(), TaskStatus.FAILED, 1, fixture.owner, FINISH_AT);
        return new FailedAttempt(failedTask, failed);
    }

    private static TaskExecution retry(FailedAttempt failed, TaskDomainFixture fixture) {
        return TaskExecution.retry(
                TaskExecutionId.generate(),
                failed.task,
                failed.execution,
                TaskExecutionPriority.NORMAL,
                UtcTimestamp.parse("2026-08-13T08:40:00Z"),
                fixture.owner,
                UtcTimestamp.parse("2026-08-13T08:35:00Z"));
    }

    private record FailedAttempt(Task task, TaskExecution execution) {}
}
