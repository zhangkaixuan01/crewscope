package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import io.crewscope.application.availability.TransitionBlockReason;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.execution.TaskApprovalInterruptTokens;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.task.AgentInterrupt;
import io.crewscope.domain.task.AgentInterruptId;
import io.crewscope.domain.task.AgentInterruptKind;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Proves the Task control availability projection and the commands that execute the controls answer
 * alike.
 *
 * <p>M9 forbids the projection and the command from each deciding availability for themselves, so this
 * test reads the projection as a promise and the command as the authority: on every cell of the
 * (status × wait reason × authority) matrix it submits each of the four control commands for real. If
 * the two ever disagree, one of them is lying to a member — either offering a control the server then
 * refuses, or withholding one that would have worked.
 *
 * <p>The expected set of offered controls is stated here independently of both sides, so a mutation
 * that disables everything (or enables everything) fails rather than passing vacuously.
 */
class TaskControlAvailabilityReconciliationTest extends MemberTaskCommandTestSupport {

    private static final TaskControlAvailabilityProjector PROJECTOR =
            new TaskControlAvailabilityProjector();

    /** The four commands {@code TaskCommandController} exposes, in the order it declares them. */
    private static final List<String> CONTROLS = List.of("pause", "resume", "cancel", "retry");

    private static final Set<String> NO_CONTROL = Set.of();

    /**
     * Every attempt state a member can find, with what the controls must offer there.
     *
     * <p>WAITING appears once per reason because the reason decides both whether Resume applies and
     * which wording a withheld control carries; every other status is unreachable with a wait reason.
     */
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario(TaskExecutionStatus.CREATED, TaskExecutionWaitReason.RUNTIME, Set.of("cancel")),
            new Scenario(TaskExecutionStatus.READY, TaskExecutionWaitReason.RUNTIME, Set.of("cancel")),
            new Scenario(TaskExecutionStatus.CLAIMED, TaskExecutionWaitReason.RUNTIME, Set.of("cancel")),
            new Scenario(
                    TaskExecutionStatus.PREPARING, TaskExecutionWaitReason.RUNTIME, Set.of("cancel")),
            new Scenario(
                    TaskExecutionStatus.RUNNING,
                    TaskExecutionWaitReason.RUNTIME,
                    Set.of("pause", "cancel")),
            new Scenario(TaskExecutionStatus.WAITING, TaskExecutionWaitReason.RUNTIME, Set.of("cancel")),
            new Scenario(
                    TaskExecutionStatus.WAITING,
                    TaskExecutionWaitReason.COLLABORATION,
                    Set.of("cancel")),
            new Scenario(TaskExecutionStatus.WAITING, TaskExecutionWaitReason.REVIEW, Set.of("cancel")),
            new Scenario(
                    TaskExecutionStatus.WAITING,
                    TaskExecutionWaitReason.CONFIRMATION,
                    Set.of("resume", "cancel")),
            new Scenario(
                    TaskExecutionStatus.WAITING, TaskExecutionWaitReason.USER_INPUT, Set.of("cancel")),
            new Scenario(
                    TaskExecutionStatus.WAITING,
                    TaskExecutionWaitReason.EXTERNAL_EXECUTION,
                    Set.of("cancel")),
            new Scenario(TaskExecutionStatus.WAITING, TaskExecutionWaitReason.EVENT, Set.of("cancel")),
            new Scenario(TaskExecutionStatus.WAITING, TaskExecutionWaitReason.MANUAL, Set.of("cancel")),
            new Scenario(
                    TaskExecutionStatus.PAUSE_REQUESTED,
                    TaskExecutionWaitReason.RUNTIME,
                    Set.of("cancel")),
            new Scenario(TaskExecutionStatus.PAUSED, TaskExecutionWaitReason.RUNTIME, Set.of("resume", "cancel")),
            new Scenario(TaskExecutionStatus.RECOVERING, TaskExecutionWaitReason.RUNTIME, Set.of("cancel")),
            new Scenario(
                    TaskExecutionStatus.CANCEL_REQUESTED,
                    TaskExecutionWaitReason.RUNTIME,
                    NO_CONTROL),
            new Scenario(
                    TaskExecutionStatus.MANUAL_TAKEOVER,
                    TaskExecutionWaitReason.RUNTIME,
                    Set.of("cancel")),
            new Scenario(TaskExecutionStatus.COMPLETED, TaskExecutionWaitReason.RUNTIME, NO_CONTROL),
            new Scenario(TaskExecutionStatus.FAILED, TaskExecutionWaitReason.RUNTIME, Set.of("retry")),
            new Scenario(TaskExecutionStatus.CANCELLED, TaskExecutionWaitReason.RUNTIME, NO_CONTROL));

    @Test
    void acceptsEveryControlTheProjectionOffersOnTheWholeMatrix() {
        int offered = 0;

        for (Scenario scenario : SCENARIOS) {
            for (boolean authorized : new boolean[] {true, false}) {
                String where = where(scenario, authorized);
                Set<String> expected = authorized ? scenario.offered() : NO_CONTROL;

                assertEquals(
                        expected,
                        new Cell(scenario, authorized).enactedControls(),
                        "the projection offers the wrong controls at " + where);

                for (String actionId : expected) {
                    assertEquals(
                            actionId,
                            new Cell(scenario, authorized).submit(actionId),
                            "the command must accept the offered control at " + where);
                    offered++;
                }
            }
        }

        assertTrue(offered > 0, "the matrix must contain at least one offered control");
    }

    @Test
    void rejectsEveryControlTheProjectionWithholdsForTheReasonItNamed() {
        int withheld = 0;

        for (Scenario scenario : SCENARIOS) {
            for (boolean authorized : new boolean[] {true, false}) {
                String where = where(scenario, authorized);
                Set<String> offered = authorized ? scenario.offered() : NO_CONTROL;

                for (String actionId : CONTROLS) {
                    if (offered.contains(actionId)) {
                        continue;
                    }
                    TransitionBlockReason reason = withheldReason(scenario, authorized);

                    // The verdict travels with the entry, so assert the projection really carries it
                    // before asking the command to agree: a missing reason is the dead control M9 set
                    // out to remove.
                    assertEquals(
                            reason,
                            new Cell(scenario, authorized).reasonFor(actionId),
                            "the projection must withhold "
                                    + actionId
                                    + " for the reason it reports at "
                                    + where);

                    // The command's own verdict: a permission refusal is the same PolicyDeniedException
                    // whichever control was asked for, and every status refusal is the same
                    // InvalidStateTransitionException. GATE_NOT_PASSED is a wording of the status
                    // refusal, so it maps there too.
                    Cell submitting = new Cell(scenario, authorized);
                    if (reason == TransitionBlockReason.PERMISSION_DENIED) {
                        assertThrows(
                                PolicyDeniedException.class,
                                () -> submitting.submit(actionId),
                                "the command must refuse the withheld control at " + where);
                    } else {
                        assertThrows(
                                InvalidStateTransitionException.class,
                                () -> submitting.submit(actionId),
                                "the command must refuse the withheld control at " + where);
                    }
                    withheld++;
                }
            }
        }

        assertTrue(withheld > 0, "the matrix must contain at least one withheld control");
    }

    /**
     * The reason a withheld control must carry.
     *
     * <p>A member who may not control the attempt is refused for the permission whatever the status,
     * because the command checks authority before it looks at the attempt. When the attempt is parked
     * on a decision only a person can make, the status refusal names that gate instead of reciting the
     * status: the same controls are refused either way, and only the explanation changes.
     */
    private static TransitionBlockReason withheldReason(Scenario scenario, boolean authorized) {
        if (!authorized) {
            return TransitionBlockReason.PERMISSION_DENIED;
        }
        return humanGate(scenario)
                ? TransitionBlockReason.GATE_NOT_PASSED
                : TransitionBlockReason.STATUS_NOT_ALLOWED;
    }

    private static boolean humanGate(Scenario scenario) {
        return scenario.status() == TaskExecutionStatus.WAITING
                && (scenario.waitingReason() == TaskExecutionWaitReason.CONFIRMATION
                        || scenario.waitingReason() == TaskExecutionWaitReason.REVIEW
                        || scenario.waitingReason() == TaskExecutionWaitReason.USER_INPUT);
    }

    private static String where(Scenario scenario, boolean authorized) {
        return scenario.status()
                + (scenario.status() == TaskExecutionStatus.WAITING
                        ? "/" + scenario.waitingReason()
                        : "")
                + (authorized ? "/owner" : "/observer");
    }

    /**
     * One prepared matrix cell: a fresh fixture walked to the scenario's attempt state, the actor whose
     * request is submitted, and the projection consulted from exactly those facts.
     *
     * <p>A cell is consumed by one judgement. The commands mutate the Task and the attempt, so a cell
     * that had already submitted a control would be judging the next verdict from the previous one's
     * aftermath rather than from the scenario.
     */
    private final class Cell {

        private final Scenario scenario;
        private final boolean authorized;
        private final TaskExecution attempt;
        private final Principal actor;
        private final boolean granted;

        Cell(Scenario scenario, boolean authorized) {
            this.scenario = scenario;
            this.authorized = authorized;
            Fixture fixture = reset();
            this.attempt = walk(scenario, fixture);
            this.actor = authorized ? owner : observer;
            this.granted = TaskControlAuthority.granted(
                    actor.id(), List.of(ownerAssignment, executorAssignment));
            executionState.set(attempt);
        }

        /** The control ids the projection reports as executable in this cell. */
        Set<String> enactedControls() {
            return PROJECTOR.all(attempt, granted).stream()
                    .filter(TaskControlAction::enabled)
                    .map(TaskControlAction::actionId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        /** The reason the projection gives for withholding one control. */
        TransitionBlockReason reasonFor(String actionId) {
            return PROJECTOR.all(attempt, granted).stream()
                    .filter(action -> action.actionId().equals(actionId))
                    .findFirst()
                    .orElseThrow()
                    .reason()
                    .orElseThrow();
        }

        /** Submits one control for real and reports what the command did. */
        String submit(String actionId) {
            String key =
                    "reconcile-" + actionId + "-" + scenario.status() + "-" + scenario.waitingReason()
                            + "-" + authorized;
            CommandExecution<MemberTaskCommandResult> execution = switch (actionId) {
                case "pause" -> service.pause(
                        context(actor, key),
                        teamId,
                        attempt.taskId(),
                        attempt.id(),
                        new MemberTaskControlCommand(attempt.version(), "Reconciled pause"));
                case "cancel" -> service.cancel(
                        context(actor, key),
                        teamId,
                        attempt.taskId(),
                        attempt.id(),
                        new MemberTaskControlCommand(attempt.version(), "Reconciled cancel"));
                case "resume" -> service.resume(
                        context(actor, key),
                        teamId,
                        attempt.taskId(),
                        attempt.id(),
                        new RetryTaskCommand(attempt.version()));
                case "retry" -> service.retry(
                        context(actor, key),
                        teamId,
                        attempt.taskId(),
                        attempt.id(),
                        new RetryTaskCommand(attempt.version()));
                default -> throw new AssertionError("no command answers " + actionId);
            };
            MemberTaskCommandResult result = execution.result().orElseThrow();
            assertEquals(
                    actionId,
                    result.operation().name().toLowerCase(java.util.Locale.ROOT),
                    "the command must report the operation it was asked for");
            assertTrue(
                    reachedTarget(actionId, result),
                    "the command must take the attempt where the projection said it would: " + actionId);
            return actionId;
        }
    }

    /**
     * Whether the committed result landed on the status the projection published.
     *
     * <p>Cancellation is the one control whose target is a request rather than a destination: an
     * attempt holding no runtime work converges to CANCELLED inside the same command, and one that is
     * still owned stops at CANCEL_REQUESTED until the Worker acknowledges. Both honour the published
     * target, so both are accepted.
     */
    private static boolean reachedTarget(String actionId, MemberTaskCommandResult result) {
        return switch (actionId) {
            case "pause" -> result.targetExecution().status() == TaskExecutionStatus.PAUSE_REQUESTED;
            case "resume" -> result.targetExecution().status() == TaskExecutionStatus.READY;
            case "retry" -> result.successorExecution()
                    .map(successor -> successor.status() == TaskExecutionStatus.READY)
                    .orElse(false);
            case "cancel" -> result.targetExecution().status() == TaskExecutionStatus.CANCEL_REQUESTED
                    || result.targetExecution().status() == TaskExecutionStatus.CANCELLED;
            default -> false;
        };
    }

    /** Walks a fresh fixture's first attempt to the scenario's state, with the runtime facts it needs. */
    private TaskExecution walk(Scenario scenario, Fixture fixture) {
        TaskExecution ready = fixture.execution();
        return switch (scenario.status()) {
            // The fixture's first attempt keeps one identity across its whole lifecycle, so the Task's
            // current attempt already points at the CREATED row; nothing has to be re-pointed.
            case CREATED -> fixture.created();
            case READY -> ready;
            case CLAIMED -> claim(ready);
            case PREPARING -> preparing(ready);
            case RUNNING -> running(ready);
            case WAITING -> waiting(scenario, ready);
            case PAUSE_REQUESTED -> {
                TaskExecution runningAttempt = running(ready);
                yield runningAttempt.requestPause(
                        "Reconciled pause", runningAttempt.version(), executor, LATER);
            }
            case PAUSED -> {
                TaskExecution runningAttempt = running(ready);
                TaskExecution requested = runningAttempt.requestPause(
                        "Reconciled pause", runningAttempt.version(), executor, LATER);
                stubPauseInterrupt(runningAttempt, requested);
                yield requested.acknowledgePaused(requested.version(), executor, LATER);
            }
            case CANCEL_REQUESTED -> ready.requestCancel(
                    "Reconciled cancel", ready.version(), executor, LATER);
            case CANCELLED -> {
                TaskExecution requested = ready.requestCancel(
                        "Reconciled cancel", ready.version(), executor, LATER);
                yield requested.acknowledgeCancelled(requested.version(), executor, LATER);
            }
            case RECOVERING -> {
                TaskExecution claimed = claim(ready);
                yield claimed.beginRecovery(claimed.version(), executor, LATER);
            }
            case MANUAL_TAKEOVER -> {
                TaskExecution runningAttempt = running(ready);
                yield runningAttempt.beginManualTakeover(
                        runningAttempt.version(), executor, LATER);
            }
            case COMPLETED -> {
                TaskExecution runningAttempt = running(ready);
                yield runningAttempt.complete(runningAttempt.version(), executor, LATER);
            }
            case FAILED -> failed(ready, true);
        };
    }

    private TaskExecution waiting(Scenario scenario, TaskExecution ready) {
        if (scenario.waitingReason() == TaskExecutionWaitReason.RUNTIME) {
            return ready.waitForRuntime(ready.version(), executor, LATER);
        }
        TaskExecution runningAttempt = running(ready);
        if (scenario.waitingReason() == TaskExecutionWaitReason.CONFIRMATION) {
            stubApprovalInterrupt(runningAttempt);
        }
        return runningAttempt.waitFor(
                scenario.waitingReason(), runningAttempt.version(), executor, LATER);
    }

    private TaskExecution claim(TaskExecution ready) {
        return ready.claim(ready.version(), executor, LATER);
    }

    private TaskExecution preparing(TaskExecution ready) {
        TaskExecution claimed = claim(ready);
        return claimed.beginPreparing(claimed.version(), executor, LATER);
    }

    /**
     * Puts the interrupted run and the pending Pause interrupt the Resume command resolves.
     *
     * <p>These live outside the attempt, so no projection can see them. The matrix supplies them
     * exactly where Resume claims to work, which is what makes the claim falsifiable: if the projection
     * offered Resume anywhere else, the command would fail and this test would say so.
     */
    private void stubPauseInterrupt(TaskExecution runningAttempt, TaskExecution requested) {
        String token = TaskControlRequestIds.from(
                        requested.id(), requested.controlRequest().orElseThrow())
                .toString();
        Runtime runtime = interruptRun(runningAttempt, AgentInterruptKind.PAUSE, token);
        when(runs.findByExecution(organizationId, requested.id()))
                .thenReturn(List.of(runtime.run()));
        when(interrupts.findPendingByRun(organizationId, runtime.run().id()))
                .thenReturn(Optional.of(runtime.interrupt()));
    }

    private void stubApprovalInterrupt(TaskExecution runningAttempt) {
        AgentRun active = runningRun(runningAttempt);
        String token = TaskApprovalInterruptTokens.from(
                        runningAttempt.id(), active.id(), active.currentSegment().sequence())
                .value();
        Runtime runtime = interruptRun(active, AgentInterruptKind.APPROVAL, token);
        // A wait keeps the attempt's identity, so the run hangs off the same execution the command
        // will look for once the attempt has parked in WAITING.
        when(runs.findByExecution(organizationId, runningAttempt.id()))
                .thenReturn(List.of(runtime.run()));
        when(interrupts.findPendingByRun(organizationId, runtime.run().id()))
                .thenReturn(Optional.of(runtime.interrupt()));
    }

    private Runtime interruptRun(
            TaskExecution runningAttempt, AgentInterruptKind kind, String token) {
        return interruptRun(runningRun(runningAttempt), kind, token);
    }

    private Runtime interruptRun(AgentRun active, AgentInterruptKind kind, String token) {
        AgentInterrupt interrupt = AgentInterrupt.open(
                AgentInterruptId.generate(),
                active,
                kind,
                RuntimeContentHash.sha256(token),
                executor,
                LATER);
        return new Runtime(
                active.interrupt(interrupt, active.version(), executor, LATER), interrupt);
    }

    private AgentRun runningRun(TaskExecution runningAttempt) {
        TaskAgentRuntimeSession session = TaskAgentRuntimeSession.initializeTask(
                taskState.get(), runningAttempt, profile, executor, NOW);
        return AgentRun.start(AgentRunId.generate(), session, 1, executor, NOW);
    }

    /** The interrupted run and the pending interrupt the Resume command has to resolve. */
    private record Runtime(AgentRun run, AgentInterrupt interrupt) {}

    /** One cell's inputs and the controls the state machine and the gate rules allow there. */
    private record Scenario(
            TaskExecutionStatus status, TaskExecutionWaitReason waitingReason, Set<String> offered) {

        private Scenario {
            offered = Set.copyOf(offered);
        }
    }
}
