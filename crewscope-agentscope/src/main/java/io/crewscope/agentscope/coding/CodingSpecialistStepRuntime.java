package io.crewscope.agentscope.coding;

import io.crewscope.application.coding.output.CodingOutputValidationException;
import io.crewscope.application.coding.output.CodingOutputValidator;
import io.crewscope.application.execution.ExecutionInterruptToken;
import io.crewscope.application.execution.TaskAgentStateRecoveryResult;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.domain.coding.CodingCheckpointId;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.task.StepExecutionStatus;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;

/**
 * Runs one serial Coding Step over M3 durable execution facts.
 *
 * <p>The model may propose a result, while Git, Workspace and TestEvidence remain authoritative.
 * Every finite call publishes an event before AgentState and CodingCheckpoint metadata.
 */
public final class CodingSpecialistStepRuntime {

    private final AgentScopeCodingRuntime runtime;
    private final CodingSpecialistAuthorityGateway authorityGateway;
    private final CodingSpecialistExecutionStore executionStore;
    private final CodingOutputValidator outputValidator;
    private final ConcurrentMap<String, ActiveExecution> active = new ConcurrentHashMap<>();

    public CodingSpecialistStepRuntime(
            AgentScopeCodingRuntime runtime,
            CodingSpecialistAuthorityGateway authorityGateway,
            CodingSpecialistExecutionStore executionStore,
            CodingOutputValidator outputValidator) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.authorityGateway = Objects.requireNonNull(authorityGateway, "authorityGateway");
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
        this.outputValidator = Objects.requireNonNull(outputValidator, "outputValidator");
    }

    public Mono<CodingSpecialistStepResult> execute(CodingSpecialistStepRequest request) {
        CodingSpecialistStepRequest required = Objects.requireNonNull(request, "request");
        requireSpecialistBoundary(required.facts());
        return Mono.defer(() -> {
            String key = executionKey(required.facts());
            ActiveExecution state = new ActiveExecution(required);
            if (active.putIfAbsent(key, state) != null) {
                return Mono.error(new IllegalStateException(
                        "Coding Step already has an active execution"));
            }
            return initialize(state)
                    .then(runRound(state, 1, Optional.empty(), 0))
                    .doFinally(ignored -> active.remove(key, state));
        });
    }

    /** Registers a durable user intent already accepted by the M3 control plane. */
    public CodingSpecialistControlResult control(
            TaskExecutionRuntimeFacts facts,
            CodingSpecialistControlAction action,
            Optional<ExecutionInterruptToken> pauseToken,
            String reason) {
        TaskExecutionRuntimeFacts requiredFacts = Objects.requireNonNull(facts, "facts");
        CodingSpecialistControlAction requiredAction = Objects.requireNonNull(action, "action");
        Optional<ExecutionInterruptToken> token = Objects.requireNonNull(pauseToken, "pauseToken");
        String safeReason = Objects.requireNonNull(reason, "reason").strip();
        if (safeReason.isEmpty() || safeReason.length() > 500) {
            throw new IllegalArgumentException("reason must be non-blank and bounded");
        }
        if ((requiredAction == CodingSpecialistControlAction.PAUSE) != token.isPresent()) {
            throw new IllegalArgumentException("PAUSE requires an interrupt token");
        }
        ActiveExecution execution = active.get(executionKey(requiredFacts));
        if (execution == null || !sameControlBoundary(
                execution.request.facts(), requiredFacts)) {
            return new CodingSpecialistControlResult(false, false);
        }
        synchronized (execution) {
            if (execution.terminal || execution.signal.get() != null) {
                return new CodingSpecialistControlResult(false, false);
            }
            execution.signal.set(new ControlSignal(requiredAction, token, safeReason));
        }
        return new CodingSpecialistControlResult(
                true, runtime.interrupt(requiredFacts.runtimeSession()));
    }

    private Mono<Void> initialize(ActiveExecution state) {
        return Mono.fromRunnable(() -> {
            executionStore.beginStep(state.request.facts(), state.request.executor());
            if (state.request.recover()) {
                // Workspace reconciliation must precede AgentState restoration so resumed Tools
                // can only observe the verified M4-I10 resource generation.
                authorityGateway.recover(state.request.facts());
                TaskAgentStateRecoveryResult recovered = executionStore.recoverState(
                        state.request.facts(), state.request.recoveryCandidateLimit());
                runtime.restore(state.request.facts().runtimeSession(), recovered.agentStateJson());
            }
        });
    }

    private Mono<CodingSpecialistStepResult> runRound(
            ActiveExecution state,
            int roundNumber,
            Optional<TestEvidence> previousFailedEvidence,
            int repairRounds) {
        return Mono.defer(() -> {
            ControlSignal pending = state.signal.get();
            if (pending != null && state.lastState != null && state.lastAuthority != null) {
                return finishControl(state, pending, roundNumber - 1, repairRounds);
            }
            CodingSpecialistRound round = authorityGateway.openRound(
                    state.request.facts(), roundNumber, previousFailedEvidence);
            if (round.number() != roundNumber) {
                return Mono.error(new IllegalStateException(
                        "Authority Gateway returned a different repair round"));
            }
            CodingSpecialistRequest invocation = new CodingSpecialistRequest(
                    state.request.facts().runtimeSession(), round.toolkit(), round.instruction());
            state.lastInvocation = invocation;
            // Materialize only the AgentScope call. Durability or authority failures from
            // afterCall must propagate and must never be rewritten onto the same event sequence.
            return runtime.execute(invocation)
                    .materialize()
                    .flatMap(signal -> {
                        if (signal.hasValue()) {
                            return afterCall(
                                    state, signal.get(), roundNumber, repairRounds);
                        }
                        if (signal.isOnError()) {
                            return interruptedOrFailed(
                                    state,
                                    roundNumber,
                                    repairRounds);
                        }
                        return Mono.error(new IllegalStateException(
                                "Coding Specialist call completed without a result"));
                    });
        });
    }

    private Mono<CodingSpecialistStepResult> afterCall(
            ActiveExecution state,
            CodingSpecialistRunResult result,
            int roundNumber,
            int repairRounds) {
        return Mono.fromCallable(() -> authorityGateway.inspect(
                        state.request.facts(), roundNumber))
                .flatMap(authority -> {
                    state.lastState = result.stateSnapshot();
                    state.lastAuthority = authority;
                    ControlSignal signal = state.signal.get();
                    if (signal != null) {
                        return finishControl(state, signal, roundNumber, repairRounds);
                    }
                    CodingSpecialistCheckpointReceipt checkpoint = executionStore.checkpoint(
                            checkpointCommand(
                                    state,
                                    result.stateSnapshot(),
                                    authority,
                                    CodingSpecialistCheckpointKind.PROGRESS,
                                    "Coding round " + roundNumber + " reached a safe point",
                                    Optional.empty()));
                    state.lastCheckpoint = checkpoint.checkpoint().id();
                    state.nextEventSequence++;

                    Optional<TestEvidence> evidence = authority.testEvidence();
                    if (evidence.isEmpty() || !evidence.orElseThrow().succeeded()) {
                        int currentMaximum = authority.policy()
                                .operationBudget()
                                .maxTestRepairRounds();
                        state.repairCeiling = state.repairCeiling < 0
                                ? currentMaximum
                                : Math.min(state.repairCeiling, currentMaximum);
                        int maximumRepairs = state.repairCeiling;
                        if (repairRounds >= maximumRepairs) {
                            return fail(
                                    state,
                                    roundNumber,
                                    repairRounds,
                                    "TEST_REPAIR_BUDGET_EXHAUSTED",
                                    false);
                        }
                        return runRound(
                                state,
                                roundNumber + 1,
                                evidence,
                                repairRounds + 1);
                    }
                    try {
                        outputValidator.validateCodeChangeResult(
                                result.output(),
                                authority.repositoryAnalysis(),
                                authority.target(),
                                authority.workspace(),
                                authority.finalDiffArtifact().orElseThrow(() ->
                                        new IllegalStateException(
                                                "Final DiffArtifact is absent after successful tests")),
                                evidence.orElseThrow());
                    } catch (CodingOutputValidationException | IllegalStateException invalid) {
                        return fail(
                                state,
                                roundNumber,
                                repairRounds,
                                "CODING_RESULT_INVALID",
                                false);
                    }
                    ControlSignal lateSignal;
                    synchronized (state) {
                        lateSignal = state.signal.get();
                        if (lateSignal == null) {
                            state.terminal = true;
                        }
                    }
                    if (lateSignal != null) {
                        return finishControl(state, lateSignal, roundNumber, repairRounds);
                    }
                    executionStore.succeed(
                            state.request.facts(),
                            state.nextEventSequence,
                            state.request.executor(),
                            state.request.correlationId());
                    return Mono.just(new CodingSpecialistStepResult(
                            CodingSpecialistStepStatus.SUCCEEDED,
                            roundNumber,
                            repairRounds,
                            Optional.of(result.output()),
                            Optional.of(checkpoint.checkpoint().id()),
                            Optional.empty()));
                });
    }

    private Mono<CodingSpecialistStepResult> interruptedOrFailed(
            ActiveExecution state,
            int roundNumber,
            int repairRounds) {
        ControlSignal signal = state.signal.get();
        if (signal == null || state.lastInvocation == null) {
            return fail(state, roundNumber, repairRounds, "CODING_RUNTIME_FAILED", true);
        }
        return Mono.fromCallable(() -> {
                    CodingSpecialistStateSnapshot snapshot = runtime.snapshot(state.lastInvocation);
                    CodingSpecialistAuthority authority = authorityGateway.inspect(
                            state.request.facts(), roundNumber);
                    state.lastState = snapshot;
                    state.lastAuthority = authority;
                    return signal;
                })
                .materialize()
                .flatMap(snapshotSignal -> snapshotSignal.hasValue()
                        ? finishControl(state, signal, roundNumber, repairRounds)
                        : fail(
                                state,
                                roundNumber,
                                repairRounds,
                                "CODING_STATE_UNAVAILABLE",
                                true));
    }

    private Mono<CodingSpecialistStepResult> finishControl(
            ActiveExecution state,
            ControlSignal signal,
            int modelCalls,
            int repairRounds) {
        return Mono.fromCallable(() -> {
            synchronized (state) {
                state.terminal = true;
            }
            CodingSpecialistCheckpointKind kind = signal.action
                    == CodingSpecialistControlAction.PAUSE
                    ? CodingSpecialistCheckpointKind.PAUSED
                    : CodingSpecialistCheckpointKind.CANCELLED;
            CodingSpecialistCheckpointReceipt checkpoint = executionStore.checkpoint(
                    checkpointCommand(
                            state,
                            Objects.requireNonNull(state.lastState, "lastState"),
                            Objects.requireNonNull(state.lastAuthority, "lastAuthority"),
                            kind,
                            signal.reason,
                            signal.pauseToken));
            state.lastCheckpoint = checkpoint.checkpoint().id();
            return new CodingSpecialistStepResult(
                    signal.action == CodingSpecialistControlAction.PAUSE
                            ? CodingSpecialistStepStatus.PAUSED
                            : CodingSpecialistStepStatus.CANCELLED,
                    modelCalls,
                    repairRounds,
                    Optional.empty(),
                    Optional.of(checkpoint.checkpoint().id()),
                    Optional.empty());
        });
    }

    private Mono<CodingSpecialistStepResult> fail(
            ActiveExecution state,
            int modelCalls,
            int repairRounds,
            String code,
            boolean retryable) {
        return Mono.fromCallable(() -> {
            synchronized (state) {
                state.terminal = true;
            }
            executionStore.fail(
                    state.request.facts(),
                    state.nextEventSequence,
                    code,
                    retryable,
                    state.request.executor(),
                    state.request.correlationId());
            return new CodingSpecialistStepResult(
                    CodingSpecialistStepStatus.FAILED,
                    modelCalls,
                    repairRounds,
                    Optional.empty(),
                    Optional.ofNullable(state.lastCheckpoint),
                    Optional.of(code));
        });
    }

    private CodingSpecialistCheckpointCommand checkpointCommand(
            ActiveExecution state,
            CodingSpecialistStateSnapshot snapshot,
            CodingSpecialistAuthority authority,
            CodingSpecialistCheckpointKind kind,
            String summary,
            Optional<ExecutionInterruptToken> token) {
        return new CodingSpecialistCheckpointCommand(
                state.request.facts(),
                snapshot,
                authority,
                kind,
                state.nextEventSequence,
                summary,
                token,
                state.request.executor(),
                state.request.correlationId());
    }

    private static void requireSpecialistBoundary(TaskExecutionRuntimeFacts facts) {
        TaskExecutionRuntimeFacts required = Objects.requireNonNull(facts, "facts");
        var step = required.stepExecution().orElseThrow(() ->
                new IllegalArgumentException("Coding Specialist requires a StepExecution"));
        if (required.runtimeSession().purpose() != TaskAgentSessionPurpose.SPECIALIST
                || (step.status() != StepExecutionStatus.READY
                        && step.status() != StepExecutionStatus.RUNNING
                        && step.status() != StepExecutionStatus.WAITING)) {
            throw new IllegalArgumentException(
                    "Coding Specialist requires a current Specialist Session and executable Step");
        }
    }

    private static String executionKey(TaskExecutionRuntimeFacts facts) {
        return facts.execution().id() + ":" + facts.stepExecution().orElseThrow().id()
                + ":" + facts.runtimeSession().agentScopeKey().sessionId();
    }

    private static boolean sameControlBoundary(
            TaskExecutionRuntimeFacts active, TaskExecutionRuntimeFacts candidate) {
        return active == candidate
                || (active.execution().id().equals(candidate.execution().id())
                && active.execution().attempt() == candidate.execution().attempt()
                && active.lease().id().equals(candidate.lease().id())
                && active.lease().fencingToken().equals(candidate.lease().fencingToken())
                && active.agentRun().id().equals(candidate.agentRun().id())
                && active.agentRun().currentSegment().sequence()
                        == candidate.agentRun().currentSegment().sequence());
    }

    private static final class ActiveExecution {
        private final CodingSpecialistStepRequest request;
        private final AtomicReference<ControlSignal> signal = new AtomicReference<>();
        private long nextEventSequence;
        private volatile CodingSpecialistRequest lastInvocation;
        private volatile CodingSpecialistStateSnapshot lastState;
        private volatile CodingSpecialistAuthority lastAuthority;
        private volatile CodingCheckpointId lastCheckpoint;
        private int repairCeiling = -1;
        private volatile boolean terminal;

        private ActiveExecution(CodingSpecialistStepRequest request) {
            this.request = request;
            this.nextEventSequence = request.nextEventSequence();
        }
    }

    private record ControlSignal(
            CodingSpecialistControlAction action,
            Optional<ExecutionInterruptToken> pauseToken,
            String reason) {}
}
