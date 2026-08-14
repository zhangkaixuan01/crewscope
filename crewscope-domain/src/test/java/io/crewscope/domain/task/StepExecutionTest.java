package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StepExecutionTest {

    @Test
    void createsStepFromCurrentPlanWithoutAnyStepLeaseFact() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();

        StepExecution step = step(fixture, graph, 0, 2);

        assertEquals(StepExecutionStatus.PENDING, step.status());
        assertEquals(graph.plan().id(), step.planVersionId());
        assertEquals(graph.policy().id(), step.policySnapshotId());
        assertEquals(fixture.base.executor.id(), step.executionPrincipal().principalId());
        assertEquals(1, step.runAttempt());
        assertEquals(2, step.maxRunAttempts());
    }

    @Test
    void runsSerialLifecycleWithExplicitWaitAndMonotonicCheckpoints() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();
        StepExecution running = step(fixture, graph, 0, 2)
                .markReady(0, fixture.base.owner, TaskPlanningFixture.STEP_AT)
                .beginRunning(1, fixture.base.executor, TaskPlanningFixture.STEP_AT);

        StepExecution firstCheckpoint = running.recordCheckpoint(
                "ANALYSIS_STARTED", TaskFactHash.sha256("one"), 2,
                fixture.base.executor, TaskPlanningFixture.STEP_AT);
        StepExecution waiting = firstCheckpoint.waitFor(
                StepWaitReason.USER_INPUT, 3, fixture.base.executor, TaskPlanningFixture.LATER);
        StepExecution secondCheckpoint = waiting.recordCheckpoint(
                "INPUT_CAPTURED", TaskFactHash.sha256("two"), 4,
                fixture.base.executor, TaskPlanningFixture.LATER);
        StepExecution resumed = secondCheckpoint
                .markReady(5, fixture.base.owner, TaskPlanningFixture.LATER)
                .beginRunning(6, fixture.base.executor, TaskPlanningFixture.LATER)
                .succeed(7, fixture.base.executor, TaskPlanningFixture.LATER);

        assertEquals(1, firstCheckpoint.checkpoint().orElseThrow().sequence());
        assertEquals(StepWaitReason.USER_INPUT, waiting.waitReason().orElseThrow());
        assertEquals(2, secondCheckpoint.checkpoint().orElseThrow().sequence());
        assertEquals(StepExecutionStatus.SUCCEEDED, resumed.status());
        assertTrue(resumed.status().isTerminal());
    }

    @Test
    void retriesRetryableFailureWithinBudgetAndPreservesCheckpoint() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();
        StepExecution running = step(fixture, graph, 0, 2)
                .markReady(0, fixture.base.owner, TaskPlanningFixture.STEP_AT)
                .beginRunning(1, fixture.base.executor, TaskPlanningFixture.STEP_AT)
                .recordCheckpoint(
                        "WORK_SAVED", TaskFactHash.sha256("checkpoint"), 2,
                        fixture.base.executor, TaskPlanningFixture.STEP_AT);
        StepExecution failed = running.fail(
                new TaskExecutionFailure(TaskExecutionFailureClass.TRANSIENT, "RUNTIME_LOST"),
                3, fixture.base.executor, TaskPlanningFixture.LATER);

        StepExecution retried = failed.markReady(
                4, fixture.base.owner, TaskPlanningFixture.LATER);

        assertEquals(StepExecutionStatus.FAILED_RETRYABLE, failed.status());
        assertEquals(2, retried.runAttempt());
        assertEquals(1, retried.checkpoint().orElseThrow().sequence());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> retried.markReady(5, fixture.base.owner, TaskPlanningFixture.LATER));
    }

    @Test
    void exhaustsRetryBudgetIntoFinalFailure() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();
        StepExecution running = step(fixture, graph, 0, 1)
                .markReady(0, fixture.base.owner, TaskPlanningFixture.STEP_AT)
                .beginRunning(1, fixture.base.executor, TaskPlanningFixture.STEP_AT);

        StepExecution failed = running.fail(
                new TaskExecutionFailure(TaskExecutionFailureClass.TRANSIENT, "TIMEOUT"),
                2, fixture.base.executor, TaskPlanningFixture.LATER);

        assertEquals(StepExecutionStatus.FAILED_FINAL, failed.status());
        assertFalse(failed.failure().isEmpty());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> failed.markReady(3, fixture.base.owner, TaskPlanningFixture.LATER));
    }

    @Test
    void onlyPinnedExecutorCanRunCheckpointOrFinish() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();
        StepExecution ready = step(fixture, graph, 0, 2)
                .markReady(0, fixture.base.owner, TaskPlanningFixture.STEP_AT);

        assertThrows(
                DomainValidationException.class,
                () -> ready.beginRunning(
                        1, fixture.base.reviewer, TaskPlanningFixture.STEP_AT));
    }

    @Test
    void criticalStepCannotBeSkippedAndTerminalCannotMutate() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();
        StepExecution critical = step(fixture, graph, 0, 2);
        StepExecution succeeded = critical
                .markReady(0, fixture.base.owner, TaskPlanningFixture.STEP_AT)
                .beginRunning(1, fixture.base.executor, TaskPlanningFixture.STEP_AT)
                .succeed(2, fixture.base.executor, TaskPlanningFixture.LATER);

        assertThrows(
                DomainValidationException.class,
                () -> critical.skip(0, fixture.base.owner, TaskPlanningFixture.STEP_AT));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> succeeded.cancel(3, fixture.base.owner, TaskPlanningFixture.LATER));
        assertThrows(
                OptimisticLockConflictException.class,
                () -> succeeded.cancel(2, fixture.base.owner, TaskPlanningFixture.LATER));
    }

    @Test
    void skipsNonCriticalPendingStepWithoutRunningIt() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();
        PlanStep optional = new PlanStep(
                "optional-check", 1, "Optional check", PlanStepType.VALIDATION, Set.of(),
                Set.of(ExecutionCapability.STRUCTURED_OUTPUT), Set.of("validation.run"), false);
        ProposedPlan candidate = ProposedPlan.of("# Optional", java.util.List.of(optional));
        PlanVersion plan = PlanVersion.publishReplacement(
                PlanVersionId.generate(), graph.plan(), fixture.task, graph.execution(),
                PlanChangeReason.MANUAL_REVISION, candidate, java.util.List.of(), graph.policy(),
                graph.overlay(), fixture.base.owner, TaskPlanningFixture.LATER);
        TaskExecution selected = graph.execution().switchCurrentPlan(
                plan, Optional.of(graph.plan().id()), 2, fixture.base.owner,
                TaskPlanningFixture.LATER);
        StepExecution step = StepExecution.create(
                StepExecutionId.generate(), fixture.task, selected, plan, optional, 1,
                fixture.base.owner, TaskPlanningFixture.LATER);

        StepExecution skipped = step.skip(
                0, fixture.base.owner, TaskPlanningFixture.LATER);

        assertEquals(StepExecutionStatus.SKIPPED, skipped.status());
        assertTrue(skipped.status().isTerminal());
    }

    @Test
    void rejectsStepFromNonCurrentPlanOrInvalidReconstitutedShape() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();
        TaskExecution withoutSelectedPlan = fixture.execution.initializePlanningContext(
                graph.policy(), graph.overlay(), 0, fixture.base.owner,
                TaskPlanningFixture.POLICY_AT);

        assertThrows(
                DomainValidationException.class,
                () -> StepExecution.create(
                        StepExecutionId.generate(), fixture.task, withoutSelectedPlan,
                        graph.plan(), graph.plan().steps().get(0), 2, fixture.base.owner,
                        TaskPlanningFixture.STEP_AT));

        StepExecution step = step(fixture, graph, 0, 2);
        assertThrows(
                DomainValidationException.class,
                () -> StepExecution.reconstitute(
                        step.id(), step.scope(), step.taskId(), step.executionId(),
                        step.planVersionId(), step.planVersionHash(), step.planStepKey(),
                        step.sequence(), step.critical(), step.executionPrincipal(),
                        step.policySnapshotId(), step.policySnapshotHash(), step.safetyOverlay(),
                        1, 2, StepExecutionStatus.WAITING, Optional.empty(), Optional.empty(),
                        Optional.empty(), 0, step.audit()));
    }

    @Test
    void rejectsCheckpointOutsideExecutorAndAuditBoundary() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();
        StepExecution step = step(fixture, graph, 0, 2);
        StepCheckpoint invalidCheckpoint = new StepCheckpoint(
                1, "INVALID_OWNER", TaskFactHash.sha256("payload"),
                fixture.base.reviewer.id(), TaskPlanningFixture.STEP_AT);

        assertThrows(
                DomainValidationException.class,
                () -> StepExecution.reconstitute(
                        step.id(), step.scope(), step.taskId(), step.executionId(),
                        step.planVersionId(), step.planVersionHash(), step.planStepKey(),
                        step.sequence(), step.critical(), step.executionPrincipal(),
                        step.policySnapshotId(), step.policySnapshotHash(), step.safetyOverlay(),
                        1, 2, StepExecutionStatus.RUNNING, Optional.empty(),
                        Optional.of(invalidCheckpoint), Optional.empty(), 1, step.audit()));
    }

    private static StepExecution step(
            TaskPlanningFixture fixture,
            TaskPlanningFixture.PlanningGraph graph,
            int planStepIndex,
            int maxRunAttempts) {
        return StepExecution.create(
                StepExecutionId.generate(), fixture.task, graph.execution(), graph.plan(),
                graph.plan().steps().get(planStepIndex), maxRunAttempts, fixture.base.owner,
                TaskPlanningFixture.STEP_AT);
    }
}
