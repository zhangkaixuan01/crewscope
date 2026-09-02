package io.crewscope.agentscope.task;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.application.execution.ExecutionFailure;
import io.crewscope.application.execution.ExecutionFailureCategory;
import io.crewscope.application.execution.ExecutionInterruptKind;
import io.crewscope.application.execution.ExecutionInterruptToken;
import io.crewscope.application.execution.RuntimeDescriptor;
import io.crewscope.application.execution.TaskExecutionControlAction;
import io.crewscope.application.execution.TaskExecutionControlRequest;
import io.crewscope.application.execution.TaskExecutionControlResult;
import io.crewscope.application.execution.TaskExecutionEvent;
import io.crewscope.application.execution.TaskExecutionEventPayload;
import io.crewscope.application.execution.TaskExecutionHandle;
import io.crewscope.application.execution.TaskExecutionRequest;
import io.crewscope.application.execution.TaskExecutionRuntime;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.execution.TaskExecutionRuntimePhase;
import io.crewscope.application.execution.AgentStateUnavailableException;
import io.crewscope.application.execution.TaskAgentStateCheckpointCommand;
import io.crewscope.application.execution.TaskAgentStateCheckpointResult;
import io.crewscope.application.execution.TaskAgentStateIdentity;
import io.crewscope.application.execution.TaskAgentStateRecoveryCommand;
import io.crewscope.application.execution.TaskAgentStateRecoveryResult;
import io.crewscope.application.execution.TaskAgentStateRuntime;
import io.crewscope.application.execution.TaskAgentStateSafePoint;
import io.crewscope.application.execution.TaskAgentStateSnapshotService;
import io.crewscope.application.execution.TaskApprovalInterruptTokens;
import io.crewscope.agentscope.TaskAgentCallObservationScope;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.task.PolicyBudget;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** AgentScope Java 2.0.0 Task Orchestrator restricted to the deterministic M3 Fixture surface. */
public final class AgentScopeTaskRuntime
        implements TaskExecutionRuntime, TaskAgentStateRuntime, AutoCloseable {

    private static final int EVENT_BUFFER_LIMIT = 10_000;
    private static final Set<String> ALLOWED_RUNTIME_TOOLS;

    static {
        HashSet<String> tools = new HashSet<>(
                io.crewscope.application.task.TaskPlanPublicationService.M3_CONTROLLED_TOOLS);
        tools.add(ControlledTaskPlanValidationTool.NAME);
        tools.addAll(Set.of("plan_enter", "plan_write", "plan_exit", "todo_write"));
        ALLOWED_RUNTIME_TOOLS = Set.copyOf(tools);
    }

    private final TaskAgentFactory agentFactory;
    private final AgentScopeTaskPlanningSnapshotMapper planningSnapshotMapper;
    private final AgentScopeTaskPlanAdapter taskPlanAdapter;
    private final TaskPlanPublisher taskPlanPublisher;
    private final TaskAgentStateSnapshotService stateSnapshotService;
    private final Clock clock;
    private final ConcurrentMap<AgentScopeExecutionKey, AgentScopeTaskExecutionState> executions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AgentScopeTaskRuntime(
            TaskAgentFactory agentFactory,
            AgentScopeTaskPlanningSnapshotMapper planningSnapshotMapper,
            AgentScopeTaskPlanAdapter taskPlanAdapter,
            TaskPlanPublisher taskPlanPublisher,
            Clock clock) {
        this(
                agentFactory,
                planningSnapshotMapper,
                taskPlanAdapter,
                taskPlanPublisher,
                new UnavailableStateSnapshotService(),
                clock);
    }

    public AgentScopeTaskRuntime(
            TaskAgentFactory agentFactory,
            AgentScopeTaskPlanningSnapshotMapper planningSnapshotMapper,
            AgentScopeTaskPlanAdapter taskPlanAdapter,
            TaskPlanPublisher taskPlanPublisher,
            TaskAgentStateSnapshotService stateSnapshotService,
            Clock clock) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.planningSnapshotMapper = Objects.requireNonNull(
                planningSnapshotMapper, "planningSnapshotMapper");
        this.taskPlanAdapter = Objects.requireNonNull(taskPlanAdapter, "taskPlanAdapter");
        this.taskPlanPublisher = Objects.requireNonNull(taskPlanPublisher, "taskPlanPublisher");
        this.stateSnapshotService = Objects.requireNonNull(
                stateSnapshotService, "stateSnapshotService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public TaskAgentStateCheckpointResult checkpointState(
            TaskExecutionRuntimeFacts facts,
            long segmentSequence,
            long eventSequence,
            TaskAgentStateSafePoint safePoint) {
        ensureOpen();
        TaskExecutionRuntimeFacts required = Objects.requireNonNull(facts, "facts");
        TaskAgentStateSafePoint point = Objects.requireNonNull(safePoint, "safePoint");
        AgentScopeTaskExecutionState execution = executions.get(AgentScopeExecutionKey.from(required));
        if (execution != null) {
            execution.requireSafeCheckpoint(point);
        }
        HarnessAgent agent = agentFactory.getOrCreate(required);
        // A CrewScope budget/control terminal uses takeUntil() to stop the AgentScope stream at
        // the durable boundary. That cancellation can precede ReActAgent's normal end-of-call
        // auto-save, so explicitly flush the call-scoped cache before reading the safe point.
        AgentScopeSessionKey key = required.runtimeSession().agentScopeKey();
        agent.getDelegate().saveAgentState(key.userId(), key.sessionId());
        AgentState state = loadHotState(agent, required);
        return stateSnapshotService.checkpoint(new TaskAgentStateCheckpointCommand(
                required,
                stateIdentity(agent, required),
                segmentSequence,
                eventSequence,
                point,
                state.toJson(),
                Optional.empty()));
    }

    @Override
    public TaskAgentStateRecoveryResult recoverState(
            TaskExecutionRuntimeFacts facts, int candidateLimit) {
        ensureOpen();
        TaskExecutionRuntimeFacts required = Objects.requireNonNull(facts, "facts");
        AgentScopeTaskExecutionState execution = executions.get(AgentScopeExecutionKey.from(required));
        if (execution != null) {
            execution.requireRecoverySafe();
        }
        HarnessAgent agent = agentFactory.getOrCreate(required);
        TaskAgentStateRecoveryResult recovered = stateSnapshotService.recover(
                new TaskAgentStateRecoveryCommand(
                        required, stateIdentity(agent, required), candidateLimit));
        AgentState state;
        try {
            state = AgentState.fromJsonString(recovered.agentStateJson());
            requireStateIdentity(required, state);
            AgentStateStore store = Objects.requireNonNull(
                    agent.getStateStore(), "Task AgentStateStore");
            AgentScopeSessionKey key = required.runtimeSession().agentScopeKey();
            store.save(key.userId(), key.sessionId(), "agent_state", state);
        } catch (RuntimeException exception) {
            throw new AgentStateUnavailableException(exception);
        }
        return recovered;
    }

    private static AgentState loadHotState(
            HarnessAgent agent, TaskExecutionRuntimeFacts facts) {
        try {
            AgentStateStore store = Objects.requireNonNull(
                    agent.getStateStore(), "Task AgentStateStore");
            AgentScopeSessionKey key = facts.runtimeSession().agentScopeKey();
            AgentState state = store.get(
                            key.userId(), key.sessionId(), "agent_state", AgentState.class)
                    .orElseThrow(() -> new IllegalStateException(
                            "Task Agent hot state is absent at the safe point"));
            requireStateIdentity(facts, state);
            return state;
        } catch (RuntimeException exception) {
            throw new AgentStateUnavailableException(exception);
        }
    }

    private static void requireStateIdentity(
            TaskExecutionRuntimeFacts facts, AgentState state) {
        AgentScopeSessionKey key = facts.runtimeSession().agentScopeKey();
        if (!key.userId().equals(state.getUserId())
                || !key.sessionId().equals(state.getSessionId())) {
            throw new IllegalArgumentException(
                    "Task AgentState identity does not match its durable Session slot");
        }
    }

    private static TaskAgentStateIdentity stateIdentity(
            HarnessAgent agent, TaskExecutionRuntimeFacts facts) {
        AgentScopeSessionKey key = facts.runtimeSession().agentScopeKey();
        // HarnessAgent.Builder#agentId configures the durable namespace, while AgentBase#getAgentId
        // is a random process-instance ID in AgentScope Java 2.0.0. The stable Harness name is the
        // namespace key selected by TaskAgentFactory and must therefore close restart recovery.
        String stableAgentId = agent.getName();
        return new TaskAgentStateIdentity(
                facts.execution().id().value(),
                facts.agentRun().id().value(),
                stableAgentId,
                stableAgentId,
                Long.toString(facts.runtimeSession().agentProfileVersion()),
                key.userId(),
                key.sessionId());
    }

    @Override
    public RuntimeDescriptor descriptor() {
        return AgentScopeTaskRuntimeProfile.descriptor();
    }

    @Override
    public RuntimeCapabilities capabilities() {
        return AgentScopeTaskRuntimeProfile.capabilities();
    }

    @Override
    public TaskExecutionHandle executeTask(TaskExecutionRequest request) {
        ensureOpen();
        TaskExecutionRequest required = Objects.requireNonNull(request, "request");
        TaskExecutionRuntimeFacts facts = required.facts();
        AgentScopeExecutionKey key = AgentScopeExecutionKey.from(facts);
        HarnessAgent agent = agentFactory.getOrCreate(facts);
        RuntimeContext context = runtimeContext(facts);
        AgentScopeTaskExecutionState state = executions.compute(key, (ignored, current) -> {
            if (current == null) {
                return new AgentScopeTaskExecutionState(key, facts, agent, context, clock);
            }
            current.rebind(facts, agent, context);
            return current;
        });
        state.beginSegment(required);
        try {
            List<Msg> input = inputMessages(required, state);
            PolicyBudget budget = facts.policySnapshot().budget();
            Duration remainingDuration = remainingDuration(facts, budget);
            Flux<AgentEvent> agentEvents = remainingDuration.isZero()
                    ? Flux.error(new TaskDurationBudgetException())
                    : agent.streamEvents(input, context)
                            .timeout(remainingDuration)
                            .contextWrite(TaskAgentCallObservationScope.install(
                                    state::observeModelTransition));
            Flux<TaskExecutionEvent> source = Flux.concat(
                            Flux.just(
                                    state.event(new TaskExecutionEventPayload.Started(
                                            facts.agentRun().currentSegment().kind())),
                                    state.event(new TaskExecutionEventPayload.StatusChanged(
                                            facts.planVersion().isEmpty()
                                                    ? TaskExecutionRuntimePhase.PLANNING
                                                    : TaskExecutionRuntimePhase.EXECUTING))),
                            agentEvents
                                    .concatMap(event -> Flux.fromIterable(
                                            mapObservedEvent(state, event)))
                                    // AgentScope normally emits AgentResultEvent. The adapter still
                                    // closes a malformed empty completion with one safe terminal.
                                    .concatWith(Flux.defer(() -> Flux.just(
                                            state.sourceCompletedWithoutResult()))))
                    .takeUntil(TaskExecutionEvent::terminal)
                    .onErrorResume(failure -> Flux.concat(
                            Flux.fromIterable(state.drainModelTransitions()),
                            Flux.just(state.sourceFailed(AgentScopeFailureClassifier.classify(failure)))))
                    .doFinally(ignored -> state.detachSegment());
            return new TaskExecutionHandle(required, startOwnedStream(source));
        } catch (RuntimeException exception) {
            // Synchronous prompt, state-load or stream-construction failure must not leave the
            // in-memory slot permanently marked as running.
            state.detachSegment();
            throw exception;
        }
    }

    @Override
    public CompletionStage<TaskExecutionControlResult> controlTask(
            TaskExecutionControlRequest request) {
        TaskExecutionControlRequest required = Objects.requireNonNull(request, "request");
        AgentScopeExecutionKey key = AgentScopeExecutionKey.from(required.facts());
        AgentScopeTaskExecutionState state = executions.get(key);
        if (state == null && required.action() == TaskExecutionControlAction.RESUME) {
            // A restarted Worker has no in-memory segment state. The durable AgentStateStore still
            // owns the pending Plan permission, so recreate only the exact fenced execution slot.
            HarnessAgent agent = agentFactory.getOrCreate(required.facts());
            RuntimeContext context = runtimeContext(required.facts());
            state = executions.computeIfAbsent(
                    key, ignored -> new AgentScopeTaskExecutionState(
                            key, required.facts(), agent, context, clock));
        } else if (state != null && required.action() == TaskExecutionControlAction.RESUME
                && !state.sameOwner(required.facts())) {
            // A WAITING/PAUSED segment releases its Lease. A later Resume can therefore arrive on
            // the same JVM with a strictly newer fencing epoch while retaining the AgentRun and
            // AgentScope Session. Advance only a safely terminated, non-logical execution slot.
            HarnessAgent agent = agentFactory.getOrCreate(required.facts());
            RuntimeContext context = runtimeContext(required.facts());
            if (!state.advanceOwnershipForResume(required.facts(), agent, context)) {
                return CompletableFuture.completedFuture(TaskExecutionControlResult.STALE_OWNER);
            }
        }
        if (state == null) {
            return CompletableFuture.completedFuture(TaskExecutionControlResult.NOT_FOUND);
        }
        return CompletableFuture.completedFuture(state.control(required));
    }

    private List<TaskExecutionEvent> mapEvent(AgentScopeTaskExecutionState state, AgentEvent event) {
        if (state.segmentTerminal()) {
            return List.of();
        }
        PolicyBudget budget = state.facts().policySnapshot().budget();
        if (event instanceof ModelCallStartEvent) {
            if (state.incrementModelCalls() > budget.maxModelCalls()) {
                state.interrupt();
                return List.of(state.failureEvent(AgentScopeFailureClassifier.budget(
                        "MODEL_CALL_BUDGET_EXCEEDED", "The model-call budget was exhausted.")));
            }
            return List.of();
        }
        if (event instanceof ModelCallEndEvent ended) {
            ChatUsage usage = ended.getUsage();
            if (usage == null) {
                return List.of();
            }
            TaskExecutionEvent usageEvent = state.usageEvent(usage);
            if (state.totalTokens() > budget.maxTokens()) {
                state.interrupt();
                return List.of(
                        usageEvent,
                        state.failureEvent(AgentScopeFailureClassifier.budget(
                                "TOKEN_BUDGET_EXCEEDED", "The token budget was exhausted.")));
            }
            return List.of(usageEvent);
        }
        if (event instanceof TextBlockDeltaEvent text) {
            return textEvents(state, text.getDelta());
        }
        if (event instanceof ToolCallStartEvent tool) {
            String name = AgentScopeTaskToolPolicy.requireAllowed(
                    ALLOWED_RUNTIME_TOOLS, tool.getToolCallName());
            AgentScopeTaskToolPolicy.requireAuthorized(state.facts(), name);
            if (state.incrementToolCalls() > budget.maxToolCalls()) {
                state.interrupt();
                return List.of(state.failureEvent(AgentScopeFailureClassifier.budget(
                        "TOOL_CALL_BUDGET_EXCEEDED", "The Tool-call budget was exhausted.")));
            }
            return List.of(state.event(new TaskExecutionEventPayload.ToolStarted(
                    requireText(tool.getToolCallId(), "toolCallId", 200), name)));
        }
        if (event instanceof ToolResultEndEvent tool) {
            String name = AgentScopeTaskToolPolicy.requireAllowed(
                    ALLOWED_RUNTIME_TOOLS, tool.getToolCallName());
            boolean success = tool.getState() == ToolResultState.SUCCESS;
            Optional<ExecutionFailure> failure = success
                    ? Optional.empty()
                    : Optional.of(new ExecutionFailure(
                            ExecutionFailureCategory.TOOL_FAILED,
                            false,
                            "A controlled runtime Tool did not complete successfully.",
                            Optional.of("CONTROLLED_TOOL_FAILED")));
            return List.of(state.event(new TaskExecutionEventPayload.ToolResult(
                    requireText(tool.getToolCallId(), "toolCallId", 200),
                    name,
                    success,
                    Optional.empty(),
                    failure)));
        }
        if (event instanceof RequireExternalExecutionEvent) {
            state.interrupt();
            return List.of(state.failureEvent(new ExecutionFailure(
                    ExecutionFailureCategory.AUTHORIZATION,
                    false,
                    "External Tool execution is disabled for the M3 Task runtime.",
                    Optional.of("EXTERNAL_TOOL_FORBIDDEN"))));
        }
        if (event instanceof RequireUserConfirmEvent confirmation) {
            return confirmationEvents(state, confirmation);
        }
        if (event instanceof ExceedMaxItersEvent) {
            state.interrupt();
            return List.of(state.failureEvent(AgentScopeFailureClassifier.budget(
                    "MAX_ITERATIONS", "The Task Agent reached its iteration limit.")));
        }
        if (event instanceof AgentResultEvent result) {
            return List.of(state.terminalEvent(result));
        }
        return List.of();
    }

    private List<TaskExecutionEvent> mapObservedEvent(
            AgentScopeTaskExecutionState state, AgentEvent event) {
        List<TaskExecutionEvent> mapped = new ArrayList<>(state.drainModelTransitions());
        mapped.addAll(mapEvent(state, event));
        return mapped;
    }

    private List<TaskExecutionEvent> confirmationEvents(
            AgentScopeTaskExecutionState state, RequireUserConfirmEvent event) {
        List<ToolUseBlock> calls = List.copyOf(event.getToolCalls());
        if (calls.size() != 1 || !"plan_exit".equals(calls.get(0).getName())) {
            state.interrupt();
            return List.of(state.failureEvent(new ExecutionFailure(
                    ExecutionFailureCategory.AUTHORIZATION,
                    false,
                    "Only controlled plan approval may interrupt the M3 Task runtime.",
                    Optional.of("UNEXPECTED_TOOL_APPROVAL"))));
        }
        state.pending(event.getReplyId(), calls);
        try {
            PlanVersionId published = publishCurrentPlan(state);
            return List.of(
                    state.event(new TaskExecutionEventPayload.PlanChanged(
                            state.publishedContentHash(), Optional.of(published))),
                    state.approvalEvent());
        } catch (RuntimeException exception) {
            return List.of(state.failureEvent(new ExecutionFailure(
                    ExecutionFailureCategory.VALIDATION,
                    false,
                    "The proposed Task plan could not be validated and published.",
                    Optional.of("TASK_PLAN_INVALID"))));
        }
    }

    private PlanVersionId publishCurrentPlan(AgentScopeTaskExecutionState state) {
        AgentState agentState = state.agent().getDelegate().getAgentState(state.context());
        String path = agentState.getPlanModeContext().getCurrentPlanFile();
        String markdown = path == null
                ? ""
                : state.agent()
                        .workspaceFor(state.key().userId(), state.key().sessionId())
                        .readManagedWorkspaceFileUtf8(state.context(), path);
        AgentScopeTaskPlanningSnapshotMapper.TaskPlanningSnapshot snapshot = planningSnapshotMapper.map(
                agentState, markdown.isBlank() ? Optional.empty() : Optional.of(markdown));
        AgentScopeTaskPlanAdapter.Candidate candidate = taskPlanAdapter.adapt(snapshot);
        state.publishedContentHash(candidate.plan().contentHash());
        return taskPlanPublisher.publish(state.facts(), candidate);
    }

    private static List<TaskExecutionEvent> textEvents(AgentScopeTaskExecutionState state, String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        List<TaskExecutionEvent> events = new ArrayList<>();
        for (int start = 0; start < value.length(); start += 10_000) {
            events.add(state.event(new TaskExecutionEventPayload.TextDelta(
                    value.substring(start, Math.min(value.length(), start + 10_000)))));
        }
        return events;
    }

    private static List<Msg> inputMessages(
            TaskExecutionRequest request, AgentScopeTaskExecutionState state) {
        AgentRunSegmentKind kind = request.facts().agentRun().currentSegment().kind();
        if (kind == AgentRunSegmentKind.RESUME && state.consumeResumeAuthorization()) {
            List<ToolUseBlock> pending = state.pendingTools();
            if (!pending.isEmpty()) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put(
                        Msg.METADATA_CONFIRM_RESULTS,
                        pending.stream()
                                .map(call -> new ConfirmResult(true, confirmed(call)))
                                .toList());
                return List.of(Msg.builder()
                        .id(request.correlationId().toString())
                        .name("crewscope-task-control")
                        .role(MsgRole.USER)
                        .textContent("[crewscope-plan-approved]")
                        .metadata(metadata)
                        .build());
            }
        }
        return List.of(UserMessage.builder()
                .id(request.correlationId().toString())
                .name("crewscope-task-orchestrator")
                .textContent(AgentScopeTaskPromptFactory.prompt(request.facts(), kind))
                .build());
    }

    private static ToolUseBlock confirmed(ToolUseBlock pending) {
        return ToolUseBlock.builder()
                .id(pending.getId())
                .name(pending.getName())
                .input(new LinkedHashMap<>(pending.getInput()))
                .content(pending.getContent())
                .metadata(pending.getMetadata())
                .state(pending.getState())
                .build();
    }

    private static RuntimeContext runtimeContext(TaskExecutionRuntimeFacts facts) {
        AgentScopeSessionKey key = facts.runtimeSession().agentScopeKey();
        return RuntimeContext.builder()
                .userId(key.userId())
                .sessionId(key.sessionId())
                .put(TaskExecutionRuntimeFacts.class, facts)
                .build();
    }

    private Duration remainingDuration(
            TaskExecutionRuntimeFacts facts, PolicyBudget budget) {
        Duration maximum = Duration.ofSeconds(budget.maxDurationSeconds());
        List<io.crewscope.domain.task.AgentRunSegment> segments = facts.agentRun().segments();
        if (segments == null || segments.isEmpty() || segments.get(0).startedAt() == null) {
            return maximum;
        }
        Duration elapsed = Duration.between(
                segments.get(0).startedAt().value(), clock.instant());
        if (elapsed.isNegative()) {
            return maximum;
        }
        Duration remaining = maximum.minus(elapsed);
        return remaining.isNegative() || remaining.isZero() ? Duration.ZERO : remaining;
    }

    private static String requireText(String value, String field, int maximumLength) {
        String required = Objects.requireNonNull(value, field).strip();
        if (required.isEmpty() || required.length() > maximumLength
                || required.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " contains invalid text");
        }
        return required;
    }

    private static Flow.Publisher<TaskExecutionEvent> startOwnedStream(
            Flux<TaskExecutionEvent> source) {
        Sinks.Many<TaskExecutionEvent> sink = Sinks.many()
                .unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<>(EVENT_BUFFER_LIMIT));
        source.subscribe(new OwnedSourceSubscriber(sink));
        return JdkFlowAdapter.publisherToFlowPublisher(sink.asFlux());
    }

    private static final class OwnedSourceSubscriber extends BaseSubscriber<TaskExecutionEvent> {

        private final Sinks.Many<TaskExecutionEvent> sink;

        private OwnedSourceSubscriber(Sinks.Many<TaskExecutionEvent> sink) {
            this.sink = sink;
        }

        @Override
        protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) {
            request(1);
        }

        @Override
        protected void hookOnNext(TaskExecutionEvent value) {
            Sinks.EmitResult result = sink.tryEmitNext(value);
            if (result == Sinks.EmitResult.FAIL_OVERFLOW
                    || result == Sinks.EmitResult.FAIL_NON_SERIALIZED
                    || result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                cancel();
                sink.tryEmitError(new IllegalStateException("Task event buffer capacity exhausted"));
                return;
            }
            // A detached transport yields FAIL_CANCELLED/FAIL_TERMINATED. The runtime-owned
            // AgentScope subscription deliberately keeps draining to its safe terminal.
            request(1);
        }

        @Override
        protected void hookOnError(Throwable throwable) {
            sink.tryEmitError(throwable);
        }

        @Override
        protected void hookOnComplete() {
            sink.tryEmitComplete();
        }
    }


    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("AgentScopeTaskRuntime is closed");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executions.values().forEach(AgentScopeTaskExecutionState::shutdown);
        executions.clear();
        agentFactory.close();
    }

    /** Keeps M3-I06 fixtures source-compatible while production wiring supplies durable storage. */
    private static final class UnavailableStateSnapshotService
            implements TaskAgentStateSnapshotService {

        @Override
        public TaskAgentStateCheckpointResult checkpoint(
                TaskAgentStateCheckpointCommand command) {
            throw new IllegalStateException("Task AgentState snapshot service is not configured");
        }

        @Override
        public TaskAgentStateRecoveryResult recover(TaskAgentStateRecoveryCommand command) {
            throw new IllegalStateException("Task AgentState snapshot service is not configured");
        }
    }
}
