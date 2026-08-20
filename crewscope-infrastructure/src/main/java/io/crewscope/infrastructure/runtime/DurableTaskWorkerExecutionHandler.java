package io.crewscope.infrastructure.runtime;

import io.crewscope.application.execution.DurableTaskExecutionEventService;
import io.crewscope.application.execution.ExecutionFailure;
import io.crewscope.application.execution.ExecutionFailureCategory;
import io.crewscope.application.execution.TaskAgentStateRuntime;
import io.crewscope.application.execution.TaskAgentStateSafePoint;
import io.crewscope.application.execution.TaskExecutionEvent;
import io.crewscope.application.execution.TaskExecutionEventPayload;
import io.crewscope.application.execution.TaskExecutionHandle;
import io.crewscope.application.execution.TaskExecutionControlAction;
import io.crewscope.application.execution.TaskExecutionControlRequest;
import io.crewscope.application.execution.TaskExecutionControlResult;
import io.crewscope.application.execution.TaskExecutionRequest;
import io.crewscope.application.execution.TaskExecutionRuntime;
import io.crewscope.application.execution.TaskExecutionTerminalStatus;
import io.crewscope.application.execution.TaskRuntimeEventCommitCommand;
import io.crewscope.application.task.AgentStateSnapshotRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.LeaseHeartbeatCommand;
import io.crewscope.application.task.LeaseReleaseCommand;
import io.crewscope.application.task.LeaseTransitionCommand;
import io.crewscope.application.task.TaskExecutionLeaseCoordinator;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskControlRequestIds;
import io.crewscope.application.task.TaskTokenRevokeCommand;
import io.crewscope.application.task.TaskTokenService;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.ClaimReceipt;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionLeaseReleaseReason;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionFailureClass;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceExecution;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs one Claim through Prepare, Token, AgentScope, receipt, Snapshot and Lease release. */
public final class DurableTaskWorkerExecutionHandler
        implements TaskWorkerExecutionHandler, AutoCloseable {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DurableTaskWorkerExecutionHandler.class);
    private static final int MAX_RELEASE_ATTEMPTS = 3;

    private final DurableTaskWorkerExecutionFactory executionFactory;
    private final TaskExecutionRuntime runtime;
    private final TaskAgentStateRuntime stateRuntime;
    private final DurableTaskExecutionEventService eventService;
    private final TaskExecutionLeaseCoordinator leaseCoordinator;
    private final TaskExecutionRepository executionRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final AgentStateSnapshotRepository snapshotRepository;
    private final TaskTokenService tokenService;
    private final AuthoritativeTimeProvider timeProvider;
    private final RuntimeWorkerRegistrationSpec registration;
    private final TaskWorkerExecutionSpec spec;
    private final ScheduledExecutorService heartbeats;
    private final ConcurrentMap<ExecutionLeaseId, ActiveExecution> active =
            new ConcurrentHashMap<>();

    public DurableTaskWorkerExecutionHandler(
            DurableTaskWorkerExecutionFactory executionFactory,
            TaskExecutionRuntime runtime,
            TaskAgentStateRuntime stateRuntime,
            DurableTaskExecutionEventService eventService,
            TaskExecutionLeaseCoordinator leaseCoordinator,
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            AgentStateSnapshotRepository snapshotRepository,
            TaskTokenService tokenService,
            AuthoritativeTimeProvider timeProvider,
            RuntimeWorkerRegistrationSpec registration,
            TaskWorkerExecutionSpec spec) {
        this.executionFactory = Objects.requireNonNull(executionFactory, "executionFactory");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.stateRuntime = Objects.requireNonNull(stateRuntime, "stateRuntime");
        this.eventService = Objects.requireNonNull(eventService, "eventService");
        this.leaseCoordinator = Objects.requireNonNull(leaseCoordinator, "leaseCoordinator");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.snapshotRepository = Objects.requireNonNull(snapshotRepository, "snapshotRepository");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.heartbeats = Executors.newScheduledThreadPool(
                Math.min(4, registration.maxConcurrentExecutions()), runnable -> {
                    Thread thread = new Thread(runnable, "crewscope-task-worker-lease-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Override
    public void execute(ClaimReceipt receipt) {
        ClaimReceipt required = Objects.requireNonNull(receipt, "receipt");
        TaskWorkerPreparedExecution prepared = null;
        ActiveExecution execution = new ActiveExecution();
        if (active.putIfAbsent(required.leaseId(), execution) != null) {
            throw new IllegalStateException("ExecutionLease is already active in this Worker");
        }
        try {
            prepared = executionFactory.prepare(required);
            execution.bind(prepared);
            if (execution.stopRequested()) {
                throw new IllegalStateException(
                        "Task Worker stop was requested during execution preparation");
            }
            recoverWhenAvailable(prepared);
            ScheduledFuture<?> heartbeat = startHeartbeat(execution);
            execution.heartbeat(heartbeat);
            authorizeResume(prepared);
            TaskExecutionHandle handle = runtime.executeTask(new TaskExecutionRequest(
                    prepared.facts(), prepared.correlationId()));
            EventSubscriber subscriber = new EventSubscriber(prepared, execution);
            execution.terminationSignal(subscriber::terminateFromOwnerState);
            handle.events().subscribe(subscriber);
            subscriber.await();
            heartbeat.cancel(false);
            Throwable streamFailure = subscriber.failure();
            if (streamFailure != null) {
                throw new IllegalStateException("Task runtime stream failed before commit", streamFailure);
            }
            TaskExecutionEvent terminal = subscriber.terminal();
            if (terminal == null) {
                throw new IllegalStateException("Task runtime stream ended without a terminal event");
            }
            checkpoint(prepared, terminal, execution.stopRequested());
            beforeRelease(prepared, terminal);
            release(prepared, terminal);
            afterRelease(prepared, terminal);
        } catch (RuntimeException failure) {
            execution.fail(failure);
            if (prepared != null) {
                abandonCodingWorkspace(prepared);
                releaseForRecovery(prepared);
            }
            throw failure;
        } finally {
            execution.cancelHeartbeat();
            if (prepared != null) {
                revokeToken(prepared);
            }
            active.remove(required.leaseId(), execution);
        }
    }

    @Override
    public void requestStop(ExecutionLeaseId leaseId) {
        ActiveExecution execution = active.get(Objects.requireNonNull(leaseId, "leaseId"));
        if (execution != null) {
            execution.requestStop();
        }
    }

    private void recoverWhenAvailable(TaskWorkerPreparedExecution prepared) {
        var facts = prepared.facts();
        boolean candidates = !snapshotRepository.findRecoveryCandidates(
                        facts.task().scope().organizationId(),
                        facts.agentRun().id(),
                        1)
                .isEmpty();
        if (candidates) {
            stateRuntime.recoverState(facts, spec.recoveryCandidateLimit());
        }
    }

    private ScheduledFuture<?> startHeartbeat(ActiveExecution execution) {
        long interval = spec.leaseHeartbeatInterval().toMillis();
        return heartbeats.scheduleWithFixedDelay(
                () -> heartbeatSafely(execution), interval, interval, TimeUnit.MILLISECONDS);
    }

    private void heartbeatSafely(ActiveExecution activeExecution) {
        if (activeExecution.stopRequested()) {
            return;
        }
        try {
            TaskWorkerPreparedExecution prepared = activeExecution.prepared();
            ExecutionLease current = requiredLease(prepared.leaseScope().leaseId());
            leaseCoordinator.heartbeat(new LeaseHeartbeatCommand(
                    prepared.leaseScope(), current.version()));
            propagateMemberControl(prepared);
        } catch (RuntimeException failure) {
            // Stop accepting runtime events after any uncertain ownership renewal. The authoritative
            // Sweeper decides whether the Lease expired; this thread never guesses ownership.
            activeExecution.fail(failure);
            activeExecution.cancelSubscription();
        }
    }

    private void authorizeResume(TaskWorkerPreparedExecution prepared) {
        var segment = prepared.facts().agentRun().currentSegment();
        if (segment.kind() != AgentRunSegmentKind.RESUME) {
            return;
        }
        UUID controlRequestId = segment.resumedFromInterruptId().orElseThrow().value();
        requireAcceptedControl(runtime.controlTask(new TaskExecutionControlRequest(
                        prepared.facts(),
                        TaskExecutionControlAction.RESUME,
                        controlRequestId,
                        "Resume the member-paused Task execution.",
                        prepared.correlationId()))
                .toCompletableFuture()
                .join());
    }

    private void propagateMemberControl(TaskWorkerPreparedExecution prepared) {
        TaskExecution current = requiredExecution(prepared.facts().execution().id());
        TaskExecutionControlAction action = switch (current.status()) {
            case PAUSE_REQUESTED -> TaskExecutionControlAction.PAUSE;
            case CANCEL_REQUESTED -> TaskExecutionControlAction.CANCEL;
            default -> null;
        };
        if (action == null) {
            return;
        }
        var request = current.controlRequest().orElseThrow();
        requireAcceptedControl(runtime.controlTask(new TaskExecutionControlRequest(
                        prepared.facts(),
                        action,
                        TaskControlRequestIds.from(current.id(), request),
                        request.reason(),
                        prepared.correlationId()))
                .toCompletableFuture()
                .join());
    }

    private static void requireAcceptedControl(TaskExecutionControlResult result) {
        if (result != TaskExecutionControlResult.ACCEPTED
                && result != TaskExecutionControlResult.ALREADY_APPLIED
                && result != TaskExecutionControlResult.ALREADY_TERMINAL) {
            throw new IllegalStateException("Task runtime rejected the durable control request: " + result);
        }
    }

    private void checkpoint(
            TaskWorkerPreparedExecution prepared,
            TaskExecutionEvent terminal,
            boolean stopRequested) {
        TaskExecutionTerminalStatus status = terminal.payload().terminalStatus().orElseThrow();
        TaskAgentStateSafePoint safePoint = stopRequested
                ? TaskAgentStateSafePoint.SHUTDOWN
                : switch (status) {
                    case INTERRUPTED -> TaskAgentStateSafePoint.INTERRUPTED;
                    case PAUSED -> TaskAgentStateSafePoint.PAUSED;
                    case COMPLETED, CANCELED, FAILED -> TaskAgentStateSafePoint.CALL_COMPLETED;
                };
        stateRuntime.checkpointState(
                prepared.facts(),
                terminal.segmentSequence(),
                terminal.sequence(),
                safePoint);
    }

    private void release(TaskWorkerPreparedExecution prepared, TaskExecutionEvent terminal) {
        for (int attempt = 1; attempt <= MAX_RELEASE_ATTEMPTS; attempt++) {
            try {
                releaseOnce(prepared, terminal);
                return;
            } catch (OptimisticLockConflictException conflict) {
                boolean taskExecutionConflict = "TaskExecution".equals(
                        conflict.error().details().get("aggregateType"));
                if (!taskExecutionConflict || attempt == MAX_RELEASE_ATTEMPTS) {
                    throw conflict;
                }
                // Member pause/cancel can commit between the Worker read and the atomic Lease
                // release. Re-read the durable control fact and let the next release converge it.
            }
        }
    }

    private void releaseOnce(TaskWorkerPreparedExecution prepared, TaskExecutionEvent terminal) {
        TaskExecution execution = requiredExecution(prepared.facts().execution().id());
        ExecutionLease lease = requiredLease(prepared.leaseScope().leaseId());
        leaseCoordinator.release(releaseCommand(prepared, execution, lease, terminal));
    }

    private LeaseReleaseCommand releaseCommand(
            TaskWorkerPreparedExecution prepared,
            TaskExecution execution,
            ExecutionLease lease,
            TaskExecutionEvent terminal) {
        LeaseTransitionCommand command = new LeaseTransitionCommand(
                prepared.leaseScope(), execution.version(), lease.version());
        TaskExecutionTerminalStatus terminalStatus = terminal.payload()
                .terminalStatus().orElseThrow();
        if (execution.status() == TaskExecutionStatus.CANCEL_REQUESTED) {
            return LeaseReleaseCommand.simple(command, ExecutionLeaseReleaseReason.CANCELLED);
        }
        boolean successfulCodingResultSealed = terminalStatus == TaskExecutionTerminalStatus.COMPLETED
                && prepared.codingWorkspace()
                        .filter(CodingWorkspaceExecution::hasSealedSuccessfulResult)
                        .isPresent();
        if (successfulCodingResultSealed) {
            return LeaseReleaseCommand.simple(command, ExecutionLeaseReleaseReason.COMPLETED);
        }
        if (execution.status() == TaskExecutionStatus.PAUSE_REQUESTED) {
            return LeaseReleaseCommand.simple(command, ExecutionLeaseReleaseReason.PAUSED);
        }
        return switch (terminalStatus) {
            case COMPLETED -> LeaseReleaseCommand.simple(
                    command, ExecutionLeaseReleaseReason.COMPLETED);
            case CANCELED -> LeaseReleaseCommand.simple(
                    command, ExecutionLeaseReleaseReason.CANCELLED);
            case PAUSED -> execution.status() == TaskExecutionStatus.PAUSE_REQUESTED
                    ? LeaseReleaseCommand.simple(command, ExecutionLeaseReleaseReason.PAUSED)
                    : waiting(command, TaskExecutionWaitReason.USER_INPUT);
            case INTERRUPTED -> waiting(command, waitReason(terminal.payload()));
            case FAILED -> failed(command, terminal.payload());
        };
    }

    private void beforeRelease(
            TaskWorkerPreparedExecution prepared, TaskExecutionEvent terminal) {
        if (prepared.codingWorkspace().isEmpty()) {
            return;
        }
        TaskExecution execution = requiredExecution(prepared.facts().execution().id());
        ExecutionLease lease = requiredLease(prepared.leaseScope().leaseId());
        executionFactory.codingLifecycle().beforeRelease(
                prepared.codingWorkspace().orElseThrow(),
                execution,
                lease,
                terminal.payload().terminalStatus().orElseThrow());
    }

    private void afterRelease(
            TaskWorkerPreparedExecution prepared, TaskExecutionEvent terminal) {
        if (prepared.codingWorkspace().isEmpty()) {
            return;
        }
        executionFactory.codingLifecycle().afterRelease(
                prepared.codingWorkspace().orElseThrow(),
                requiredExecution(prepared.facts().execution().id()),
                terminal.payload().terminalStatus().orElseThrow());
    }

    private void abandonCodingWorkspace(TaskWorkerPreparedExecution prepared) {
        prepared.codingWorkspace().ifPresent(workspace -> {
            try {
                executionFactory.codingLifecycle().abandon(workspace);
            } catch (RuntimeException cleanupFailure) {
                LOGGER.warn("Coding Workspace local cleanup deferred after {}",
                        cleanupFailure.getClass().getSimpleName());
            }
        });
    }

    private static LeaseReleaseCommand waiting(
            LeaseTransitionCommand command, TaskExecutionWaitReason reason) {
        return new LeaseReleaseCommand(
                command,
                ExecutionLeaseReleaseReason.WAITING,
                Optional.of(reason),
                Optional.empty());
    }

    private static LeaseReleaseCommand failed(
            LeaseTransitionCommand command, TaskExecutionEventPayload payload) {
        ExecutionFailure runtimeFailure = ((TaskExecutionEventPayload.Failed) payload).failure();
        TaskExecutionFailure failure = new TaskExecutionFailure(
                failureClass(runtimeFailure.category()), safeFailureCode(runtimeFailure));
        return new LeaseReleaseCommand(
                command,
                ExecutionLeaseReleaseReason.FAILED,
                Optional.empty(),
                Optional.of(failure));
    }

    private static TaskExecutionWaitReason waitReason(TaskExecutionEventPayload payload) {
        if (payload instanceof TaskExecutionEventPayload.ApprovalRequired approval) {
            return switch (approval.kind()) {
                case EXTERNAL_EXECUTION -> TaskExecutionWaitReason.EXTERNAL_EXECUTION;
                case CLARIFICATION -> TaskExecutionWaitReason.USER_INPUT;
                case TOOL_APPROVAL, POLICY_CHECKPOINT -> TaskExecutionWaitReason.CONFIRMATION;
            };
        }
        return TaskExecutionWaitReason.USER_INPUT;
    }

    private static TaskExecutionFailureClass failureClass(ExecutionFailureCategory category) {
        return switch (category) {
            case VALIDATION, MODEL_OUTPUT_INVALID -> TaskExecutionFailureClass.VALIDATION;
            case AUTHORIZATION -> TaskExecutionFailureClass.AUTHORIZATION;
            case CAPABILITY_UNAVAILABLE -> TaskExecutionFailureClass.CAPABILITY_UNSUPPORTED;
            case MODEL_RATE_LIMITED -> TaskExecutionFailureClass.RATE_LIMITED;
            case MODEL_UNAVAILABLE -> TaskExecutionFailureClass.MODEL_UNAVAILABLE;
            case TOOL_FAILED -> TaskExecutionFailureClass.TOOL_UNAVAILABLE;
            case STATE_UNAVAILABLE -> TaskExecutionFailureClass.RECOVERY_INTERRUPTED;
            case TIMEOUT -> TaskExecutionFailureClass.TIMEOUT;
            case INTERNAL -> TaskExecutionFailureClass.INTERNAL;
        };
    }

    private static String safeFailureCode(ExecutionFailure failure) {
        String source = failure.runtimeCode().orElse(failure.category().name());
        String normalized = source.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9_]", "_");
        if (normalized.isEmpty() || !Character.isLetter(normalized.charAt(0))) {
            normalized = "RUNTIME_" + normalized;
        }
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private void releaseForRecovery(TaskWorkerPreparedExecution prepared) {
        try {
            TaskExecution execution = requiredExecution(prepared.facts().execution().id());
            ExecutionLease lease = requiredLease(prepared.leaseScope().leaseId());
            if (lease.release().isPresent() || !lease.owns(
                    prepared.leaseScope().ownership(), timeProvider.now())) {
                return;
            }
            leaseCoordinator.release(LeaseReleaseCommand.simple(
                    new LeaseTransitionCommand(
                            prepared.leaseScope(), execution.version(), lease.version()),
                    ExecutionLeaseReleaseReason.WORKER_SHUTDOWN));
        } catch (RuntimeException cleanupFailure) {
            LOGGER.warn("Task Worker could not release failed ownership; Sweeper will reconcile {}",
                    cleanupFailure.getClass().getSimpleName());
        }
    }

    private void revokeToken(TaskWorkerPreparedExecution prepared) {
        try {
            tokenService.revoke(new TaskTokenRevokeCommand(
                    prepared.token().token(),
                    prepared.token().grant().version(),
                    "TASK_EXECUTION_BOUNDARY_REACHED"));
        } catch (RuntimeException cleanupFailure) {
            // Grant expiry remains the security backstop; cleanup cannot rewrite a committed result.
            LOGGER.warn("Task Worker Token cleanup deferred after {}",
                    cleanupFailure.getClass().getSimpleName());
        }
    }

    private TaskExecution requiredExecution(io.crewscope.domain.task.TaskExecutionId id) {
        return executionRepository.findById(registration.organizationId(), id)
                .orElseThrow(() -> new io.crewscope.domain.shared.error.AggregateNotFoundException(
                        "TaskExecution", id));
    }

    private ExecutionLease requiredLease(ExecutionLeaseId id) {
        return leaseRepository.findById(
                        registration.organizationId(), registration.environment(), id)
                .orElseThrow(() -> new io.crewscope.domain.shared.error.AggregateNotFoundException(
                        "ExecutionLease", id));
    }

    @Override
    public void close() {
        heartbeats.shutdownNow();
    }

    private final class EventSubscriber implements Flow.Subscriber<TaskExecutionEvent> {

        private final TaskWorkerPreparedExecution prepared;
        private final ActiveExecution execution;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<TaskExecutionEvent> terminal = new AtomicReference<>();
        private final AtomicLong lastCommittedSequence = new AtomicLong();

        private EventSubscriber(
                TaskWorkerPreparedExecution prepared, ActiveExecution execution) {
            this.prepared = prepared;
            this.execution = execution;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            execution.subscription(subscription);
            subscription.request(1);
        }

        @Override
        public void onNext(TaskExecutionEvent event) {
            if (execution.failure() != null || execution.stopRequested()) {
                execution.cancelSubscription();
                failure.compareAndSet(null, new IllegalStateException(
                        "Task ownership became uncertain before event commit"));
                finished.countDown();
                return;
            }
            try {
                ExecutionLease currentLease = requiredLease(prepared.leaseScope().leaseId());
                if (!currentLease.owns(
                        prepared.leaseScope().ownership(), timeProvider.now())) {
                    throw new IllegalStateException(
                            "Task ownership expired before runtime event commit");
                }
                eventService.commit(new TaskRuntimeEventCommitCommand(
                        prepared.facts(),
                        event,
                        prepared.correlationId(),
                        Optional.empty()));
                lastCommittedSequence.set(event.sequence());
                if (event.terminal()) {
                    terminal.set(event);
                }
                Flow.Subscription subscription = execution.subscription();
                if (subscription != null) {
                    subscription.request(1);
                }
            } catch (RuntimeException commitFailure) {
                failure.compareAndSet(null, commitFailure);
                execution.cancelSubscription();
                finished.countDown();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            failure.compareAndSet(null, throwable);
            finished.countDown();
        }

        @Override
        public void onComplete() {
            finished.countDown();
        }

        /** Unblocks the Worker because Flow cancellation does not emit a terminal signal. */
        private void terminateFromOwnerState() {
            failure.compareAndSet(null, Optional.ofNullable(execution.failure())
                    .orElseGet(() -> new IllegalStateException(
                            "Task Worker stop was requested before runtime completion")));
            execution.cancelSubscription();
            finished.countDown();
        }

        private void await() {
            try {
                finished.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Task Worker execution was interrupted", interrupted);
            }
        }

        private Throwable failure() {
            return failure.get();
        }

        private TaskExecutionEvent terminal() {
            return terminal.get();
        }
    }

    private static final class ActiveExecution {

        private final AtomicReference<TaskWorkerPreparedExecution> prepared = new AtomicReference<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<Runnable> terminationSignal = new AtomicReference<>();
        private final AtomicBoolean stopRequested = new AtomicBoolean();

        private void bind(TaskWorkerPreparedExecution value) {
            prepared.set(Objects.requireNonNull(value, "prepared"));
        }

        private TaskWorkerPreparedExecution prepared() {
            return Objects.requireNonNull(prepared.get(), "prepared execution");
        }

        private void subscription(Flow.Subscription value) {
            subscription.set(Objects.requireNonNull(value, "subscription"));
            if (stopRequested.get()) {
                value.cancel();
            }
        }

        private Flow.Subscription subscription() {
            return subscription.get();
        }

        private void heartbeat(ScheduledFuture<?> value) {
            heartbeat.set(Objects.requireNonNull(value, "heartbeat"));
        }

        private void cancelHeartbeat() {
            ScheduledFuture<?> current = heartbeat.get();
            if (current != null) {
                current.cancel(false);
            }
        }

        private void fail(Throwable value) {
            if (failure.compareAndSet(null, Objects.requireNonNull(value, "failure"))) {
                signalTermination();
            }
        }

        private Throwable failure() {
            return failure.get();
        }

        private void requestStop() {
            stopRequested.set(true);
            cancelSubscription();
            signalTermination();
        }

        private boolean stopRequested() {
            return stopRequested.get();
        }

        private void cancelSubscription() {
            Flow.Subscription current = subscription.get();
            if (current != null) {
                current.cancel();
            }
        }

        private void terminationSignal(Runnable value) {
            Runnable required = Objects.requireNonNull(value, "terminationSignal");
            if (!terminationSignal.compareAndSet(null, required)) {
                throw new IllegalStateException("Task Worker termination signal is already bound");
            }
            if (failure.get() != null || stopRequested.get()) {
                required.run();
            }
        }

        private void signalTermination() {
            Runnable current = terminationSignal.get();
            if (current != null) {
                current.run();
            }
        }
    }
}
