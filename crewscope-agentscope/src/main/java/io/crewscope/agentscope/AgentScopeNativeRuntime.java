package io.crewscope.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.application.conversation.ClarificationQuestionV1;
import io.crewscope.application.conversation.ClarificationRequestV1;
import io.crewscope.application.execution.ConversationCancelRequest;
import io.crewscope.application.execution.ConversationExecutionRequest;
import io.crewscope.application.execution.ConversationResumeRequest;
import io.crewscope.application.execution.AgentStateUnavailableException;
import io.crewscope.application.execution.ExecutionCancelResult;
import io.crewscope.application.execution.ExecutionEvent;
import io.crewscope.application.execution.ExecutionEventPayload;
import io.crewscope.application.execution.ExecutionFailure;
import io.crewscope.application.execution.ExecutionFailureCategory;
import io.crewscope.application.execution.ExecutionHandle;
import io.crewscope.application.execution.ExecutionInterruptKind;
import io.crewscope.application.execution.ExecutionInterruptToken;
import io.crewscope.application.execution.ExecutionRuntime;
import io.crewscope.application.execution.ExecutionSegmentKind;
import io.crewscope.application.execution.RuntimeCapabilities;
import io.crewscope.application.execution.RuntimeDescriptor;
import io.crewscope.application.execution.RuntimeInvocationId;
import io.crewscope.application.execution.StructuredOutputSpec;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** AgentScope Java 2.0.0 implementation of the Conversation ExecutionRuntime Port. */
public final class AgentScopeNativeRuntime implements ExecutionRuntime, AutoCloseable {

    private static final int DEFAULT_RETAINED_TERMINALS = 1_000;

    private final PersonalAgentFactory agentFactory;
    private final Clock clock;
    private final int retainedTerminalLimit;
    private final ConcurrentMap<RuntimeInvocationId, InvocationState> invocations =
            new ConcurrentHashMap<>();
    private final Queue<RuntimeInvocationId> terminalOrder = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AgentScopeNativeRuntime(PersonalAgentFactory agentFactory, Clock clock) {
        this(agentFactory, clock, DEFAULT_RETAINED_TERMINALS);
    }

    public AgentScopeNativeRuntime(
            PersonalAgentFactory agentFactory, Clock clock, int retainedTerminalLimit) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (retainedTerminalLimit < 1 || retainedTerminalLimit > 100_000) {
            throw new IllegalArgumentException(
                    "retainedTerminalLimit must be between 1 and 100000");
        }
        this.retainedTerminalLimit = retainedTerminalLimit;
    }

    @Override
    public RuntimeDescriptor descriptor() {
        return AgentScopeRuntimeProfile.descriptor();
    }

    @Override
    public RuntimeCapabilities capabilities() {
        return AgentScopeRuntimeProfile.capabilities();
    }

    @Override
    public ExecutionHandle invokeConversation(ConversationExecutionRequest request) {
        ensureOpen();
        ConversationExecutionRequest required = Objects.requireNonNull(request, "request");
        InvocationState state = InvocationState.initial(required);
        if (invocations.putIfAbsent(required.invocationId(), state) != null) {
            throw new IllegalStateException("invocationId has already been registered");
        }
        try {
            HarnessAgent agent = agentFactory.getOrCreate(required.runtimeSession());
            RuntimeContext context = runtimeContext(
                    required.runtimeSession(), required.platformContext());
            state.bind(agent, context);
            Flux<ExecutionEvent> events = required.structuredOutput()
                    .<Flux<ExecutionEvent>>map(spec -> structuredSegment(
                            state,
                            ExecutionSegmentKind.INVOKE,
                            List.of(toUserMessage(required.inputMessage())),
                            spec))
                    .orElseGet(() -> plainSegment(
                            state,
                            ExecutionSegmentKind.INVOKE,
                            List.of(toUserMessage(required.inputMessage()))));
            return startSegment(state, events);
        } catch (RuntimeException exception) {
            return startSegment(
                    state,
                    failedSegment(
                            state,
                            ExecutionSegmentKind.INVOKE,
                            configurationFailure(exception)));
        }
    }

    @Override
    public ExecutionHandle resumeConversation(ConversationResumeRequest request) {
        ensureOpen();
        ConversationResumeRequest required = Objects.requireNonNull(request, "request");
        InvocationState state = requireInvocation(required.invocationId());
        PendingInterrupt pending = state.previewResume(required);
        RuntimeContext refreshedContext = runtimeContext(
                required.runtimeSession(), required.platformContext());
        List<Msg> resumeMessages = List.of(toResumeMessage(required, pending));
        state.beginResume(required, pending, refreshedContext);
        Flux<ExecutionEvent> events;
        try {
            events = state.structuredOutput()
                    .<Flux<ExecutionEvent>>map(spec -> structuredSegment(
                            state, ExecutionSegmentKind.RESUME, resumeMessages, spec))
                    .orElseGet(() -> plainSegment(
                            state, ExecutionSegmentKind.RESUME, resumeMessages));
        } catch (RuntimeException exception) {
            // The Pending Interrupt has been consumed. Close this segment with a safe terminal
            // instead of leaving an invocation stranded in RUNNING without an event stream.
            events = failedSegment(
                    state, ExecutionSegmentKind.RESUME, executionFailure(exception));
        }
        return startSegment(state, events);
    }

    @Override
    public CompletionStage<ExecutionCancelResult> cancel(ConversationCancelRequest request) {
        ConversationCancelRequest required = Objects.requireNonNull(request, "request");
        InvocationState state = invocations.get(required.invocationId());
        if (state == null
                || !state.belongsTo(required.runtimeSession())
                || !state.belongsTo(required.platformContext())) {
            return CompletableFuture.completedFuture(ExecutionCancelResult.NOT_FOUND);
        }
        CancelDecision decision = state.requestCancel(required.reason());
        if (decision == CancelDecision.ALREADY_TERMINAL) {
            return CompletableFuture.completedFuture(ExecutionCancelResult.ALREADY_TERMINAL);
        }
        if (decision == CancelDecision.INTERRUPTED_WAIT) {
            retainTerminal(state.invocationId());
        } else {
            state.interruptAgent();
        }
        return CompletableFuture.completedFuture(ExecutionCancelResult.ACCEPTED);
    }

    private ExecutionHandle startSegment(
            InvocationState state, Flux<ExecutionEvent> source) {
        Sinks.Many<ExecutionEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        Flow.Publisher<ExecutionEvent> publisher =
                JdkFlowAdapter.publisherToFlowPublisher(sink.asFlux());
        ExecutionHandle handle = new ExecutionHandle(state.invocationId(), publisher);
        Disposable running = source.subscribe(
                event -> emitNext(sink, event),
                failure -> {
                    if (!state.isSegmentTerminal()) {
                        ExecutionEvent failed = event(
                                state,
                                state.nextSequence(),
                                state.failurePayload(executionFailure(failure)));
                        emitNext(sink, failed);
                    }
                    if (state.isLogicalTerminal()) {
                        retainTerminal(state.invocationId());
                    }
                    sink.tryEmitComplete();
                },
                () -> {
                    if (!state.isSegmentTerminal()) {
                        ExecutionEvent failed = event(
                                state,
                                state.nextSequence(),
                                state.failurePayload(incompleteStreamFailure()));
                        emitNext(sink, failed);
                    }
                    if (state.isLogicalTerminal()) {
                        retainTerminal(state.invocationId());
                    }
                    sink.tryEmitComplete();
                });
        state.attach(running);
        return handle;
    }

    private Flux<ExecutionEvent> plainSegment(
            InvocationState state, ExecutionSegmentKind segmentKind, List<Msg> messages) {
        state.beginSegment();
        SegmentCapture capture = new SegmentCapture();
        AgentScopeEventMapper eventMapper = new AgentScopeEventMapper();
        Flux<ExecutionEvent> body = state.agent()
                .streamEvents(messages, state.context())
                .handle((agentEvent, output) -> mapAgentEvent(
                                state, capture, eventMapper.map(agentEvent))
                        .ifPresent(output::next));
        return Flux.concat(Mono.just(started(state, segmentKind)), body);
    }

    private Flux<ExecutionEvent> structuredSegment(
            InvocationState state,
            ExecutionSegmentKind segmentKind,
            List<Msg> messages,
            StructuredOutputSpec<?> spec) {
        state.beginSegment();
        Flux<ExecutionEvent> body = state.agent()
                .call(messages, spec.javaType(), state.context())
                .flatMapMany(result -> structuredResultEvents(state, spec, result));
        return Flux.concat(Mono.just(started(state, segmentKind)), body);
    }

    private Flux<ExecutionEvent> structuredResultEvents(
            InvocationState state, StructuredOutputSpec<?> spec, Msg result) {
        if (isResumable(result.getGenerateReason())) {
            return Flux.just(event(
                    state,
                    state.nextSequence(),
                    state.interruptedPayload(pendingFromResult(result))));
        }
        if (result.getGenerateReason() == GenerateReason.INTERRUPTED) {
            return Flux.just(event(
                    state, state.nextSequence(), state.interruptedOrCanceledPayload()));
        }
        if (result.getGenerateReason() == GenerateReason.MAX_ITERATIONS) {
            return Flux.just(event(
                    state,
                    state.nextSequence(),
                    state.failurePayload(maxIterationsFailure())));
        }
        if (result.getGenerateReason() == GenerateReason.ALL_TOOLS_DENIED) {
            return Flux.just(event(
                    state,
                    state.nextSequence(),
                    state.failurePayload(allToolsDeniedFailure())));
        }
        try {
            Object value = result.getStructuredData(spec.javaType());
            ExecutionEvent structured = event(
                    state,
                    state.nextSequence(),
                    structuredPayload(spec, value));
            ExecutionEvent completed = event(
                    state,
                    state.nextSequence(),
                    state.completedPayload());
            return Flux.just(structured, completed);
        } catch (RuntimeException exception) {
            return Flux.just(event(
                    state,
                    state.nextSequence(),
                    state.failurePayload(structuredOutputFailure(exception))));
        }
    }

    private Optional<ExecutionEvent> mapAgentEvent(
            InvocationState state,
            SegmentCapture capture,
            AgentScopeEventMapper.Mapped mappedEvent) {
        if (state.isSegmentTerminal()) {
            // AgentScope terminal events close the current segment. Ignore any defensive cleanup
            // signals that follow so the platform contract still exposes exactly one terminal.
            return Optional.empty();
        }
        if (mappedEvent instanceof AgentScopeEventMapper.PublicText text) {
            return Optional.of(event(
                    state,
                    state.nextSequence(),
                    new ExecutionEventPayload.TextDelta(text.delta())));
        }
        if (mappedEvent instanceof AgentScopeEventMapper.UserConfirmation confirmation) {
            capture.userConfirmation(confirmation.event());
            return Optional.empty();
        }
        if (mappedEvent instanceof AgentScopeEventMapper.ExternalExecution external) {
            capture.externalExecution(external.event());
            return Optional.empty();
        }
        if (mappedEvent instanceof AgentScopeEventMapper.Stop stop) {
            capture.stop(stop.event());
            return Optional.empty();
        }
        if (mappedEvent == AgentScopeEventMapper.MaxIterations.INSTANCE) {
            capture.maxIterations = true;
            return Optional.empty();
        }
        if (mappedEvent instanceof AgentScopeEventMapper.Result result) {
            AgentResultEvent resultEvent = result.event();
            ExecutionEventPayload terminal = terminalPayload(
                    state, capture, Objects.requireNonNull(resultEvent.getResult(), "Agent result"));
            return Optional.of(event(state, state.nextSequence(), terminal));
        }
        return Optional.empty();
    }

    private ExecutionEventPayload terminalPayload(
            InvocationState state, SegmentCapture capture, Msg result) {
        if (state.cancelRequested()) {
            return state.canceledPayload();
        }
        GenerateReason reason = result.getGenerateReason();
        if (isResumable(reason)) {
            return state.interruptedPayload(capture.pending(result));
        }
        if (reason == GenerateReason.INTERRUPTED) {
            return state.interruptedOrCanceledPayload();
        }
        if (reason == GenerateReason.MAX_ITERATIONS || capture.maxIterations) {
            return state.failurePayload(maxIterationsFailure());
        }
        if (reason == GenerateReason.ALL_TOOLS_DENIED) {
            return state.failurePayload(allToolsDeniedFailure());
        }
        return state.completedPayload();
    }

    private Flux<ExecutionEvent> failedSegment(
            InvocationState state,
            ExecutionSegmentKind segmentKind,
            ExecutionFailure failure) {
        state.beginSegment();
        return Flux.just(
                started(state, segmentKind),
                event(state, state.nextSequence(), state.failurePayload(failure)));
    }

    private ExecutionEvent started(
            InvocationState state, ExecutionSegmentKind segmentKind) {
        return event(
                state,
                state.nextSequence(),
                new ExecutionEventPayload.Started(segmentKind));
    }

    private ExecutionEvent event(
            InvocationState state, long sequence, ExecutionEventPayload payload) {
        return new ExecutionEvent(
                state.invocationId(), sequence, UtcTimestamp.from(clock.instant()), payload);
    }

    private static ExecutionEventPayload.StructuredOutput<?> structuredPayload(
            StructuredOutputSpec<?> spec, Object value) {
        return structuredPayloadTyped(spec, value);
    }

    private static <T> ExecutionEventPayload.StructuredOutput<T> structuredPayloadTyped(
            StructuredOutputSpec<T> spec, Object value) {
        return new ExecutionEventPayload.StructuredOutput<>(spec, spec.requireValue(value));
    }

    private static UserMessage toUserMessage(io.crewscope.domain.conversation.Message message) {
        return UserMessage.builder()
                .id(message.id().toString())
                .name("crewscope-user")
                .textContent(message.content().markdown())
                .build();
    }

    private static Msg toResumeMessage(
            ConversationResumeRequest request, PendingInterrupt pending) {
        if (pending.toolCalls().isEmpty()) {
            return toUserMessage(request.answerMessage());
        }
        List<ConfirmResult> confirmations = pending.toolCalls().stream()
                .map(toolCall -> new ConfirmResult(true, bindAnswer(toolCall, request)))
                .toList();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Msg.METADATA_CONFIRM_RESULTS, confirmations);
        return Msg.builder()
                .id(request.answerMessage().id().toString())
                .name("crewscope-user")
                .role(MsgRole.USER)
                .textContent("[crewscope-confirmed-answer]")
                .metadata(metadata)
                .build();
    }

    private static ToolUseBlock bindAnswer(
            ToolUseBlock pending, ConversationResumeRequest request) {
        Map<String, Object> input = new LinkedHashMap<>(pending.getInput());
        if ("request_clarification".equals(pending.getName())) {
            request.clarificationAnswers()
                    .ifPresentOrElse(
                            answers -> {
                                input.remove("answer");
                                input.put("answers", answers.values());
                            },
                            () -> input.put(
                                    "answer", request.answerMessage().content().markdown()));
        }
        return ToolUseBlock.builder()
                .id(pending.getId())
                .name(pending.getName())
                .input(input)
                .content(JsonUtils.getJsonCodec().toJson(input))
                .metadata(pending.getMetadata())
                .state(pending.getState())
                .build();
    }

    private static RuntimeContext runtimeContext(
            AgentRuntimeSession session,
            io.crewscope.application.execution.PlatformExecutionContext platformContext) {
        return RuntimeContext.builder()
                .userId(session.agentScopeKey().userId())
                .sessionId(session.agentScopeKey().sessionId())
                .put(io.crewscope.application.execution.PlatformExecutionContext.class,
                        Objects.requireNonNull(platformContext, "platformContext"))
                .build();
    }

    private InvocationState requireInvocation(RuntimeInvocationId invocationId) {
        InvocationState state = invocations.get(Objects.requireNonNull(invocationId, "invocationId"));
        if (state == null) {
            throw new IllegalArgumentException("invocationId was not found");
        }
        return state;
    }

    private void retainTerminal(RuntimeInvocationId invocationId) {
        synchronized (terminalOrder) {
            terminalOrder.add(invocationId);
            while (terminalOrder.size() > retainedTerminalLimit) {
                RuntimeInvocationId oldest = terminalOrder.remove();
                invocations.computeIfPresent(
                        oldest, (ignored, state) -> state.isLogicalTerminal() ? null : state);
            }
        }
    }

    private static void emitNext(
            Sinks.Many<ExecutionEvent> sink, ExecutionEvent event) {
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result == Sinks.EmitResult.FAIL_NON_SERIALIZED
                || result == Sinks.EmitResult.FAIL_OVERFLOW) {
            sink.tryEmitError(new IllegalStateException(
                    "Execution event transport buffer rejected an event"));
        }
        // FAIL_CANCELLED and FAIL_TERMINATED mean the transport consumer has gone away; the
        // internally owned AgentScope subscription and Invocation state continue independently.
    }

    private static boolean isResumable(GenerateReason reason) {
        return reason == GenerateReason.PERMISSION_ASKING
                || reason == GenerateReason.TOOL_SUSPENDED
                || reason == GenerateReason.MIDDLEWARE_STOP_REQUESTED
                || reason == GenerateReason.REASONING_STOP_REQUESTED
                || reason == GenerateReason.ACTING_STOP_REQUESTED;
    }

    private static PendingInterrupt pendingFromResult(Msg result) {
        List<ToolUseBlock> pendingTools = result.getContentBlocks(ToolUseBlock.class).stream()
                .filter(tool -> tool.getState() == ToolCallState.ASKING
                        || tool.getState() == ToolCallState.PENDING)
                .toList();
        ExecutionInterruptKind kind = interruptKind(pendingTools, false);
        return PendingInterrupt.create(Optional.ofNullable(result.getId()), pendingTools, kind);
    }

    private static ExecutionInterruptKind interruptKind(
            List<ToolUseBlock> toolCalls, boolean external) {
        if (external) {
            return ExecutionInterruptKind.EXTERNAL_EXECUTION;
        }
        if (toolCalls.stream().anyMatch(
                tool -> "request_clarification".equals(tool.getName()))) {
            return ExecutionInterruptKind.CLARIFICATION;
        }
        return toolCalls.isEmpty()
                ? ExecutionInterruptKind.POLICY_CHECKPOINT
                : ExecutionInterruptKind.TOOL_APPROVAL;
    }

    private static String safePrompt(ExecutionInterruptKind kind) {
        return switch (kind) {
            case CLARIFICATION -> "Additional information is required to continue.";
            case TOOL_APPROVAL -> "Tool approval is required to continue.";
            case EXTERNAL_EXECUTION -> "External execution is required to continue.";
            case POLICY_CHECKPOINT -> "A policy checkpoint must be resolved to continue.";
        };
    }

    /**
     * Reduces the built-in clarification Tool input to the public, bounded application DTO.
     * Runtime-only Tool metadata and undeclared input fields never cross this boundary.
     */
    private static Optional<ClarificationRequestV1> publicClarification(
            List<ToolUseBlock> toolCalls, ExecutionInterruptKind kind) {
        if (kind != ExecutionInterruptKind.CLARIFICATION) {
            return Optional.empty();
        }
        List<ToolUseBlock> clarificationTools = toolCalls.stream()
                .filter(call -> ClarificationTool.NAME.equals(call.getName()))
                .toList();
        if (clarificationTools.size() != 1) {
            throw new IllegalArgumentException(
                    "clarification interrupt must retain exactly one built-in Tool request");
        }
        ToolUseBlock tool = clarificationTools.get(0);
        Map<?, ?> request = requirePublicMap(tool.getInput().get("request"), "request");
        String schemaVersion = requirePublicText(
                request.get("schemaVersion"), "schemaVersion", 1, 1);
        if (!ClarificationRequestV1.SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("clarification schemaVersion must be 1");
        }
        String summary = requirePublicText(request.get("summary"), "summary", 1, 1_000);
        Object rawQuestions = request.get("questions");
        if (!(rawQuestions instanceof List<?> questions)
                || questions.isEmpty()
                || questions.size() > 10) {
            throw new IllegalArgumentException("clarification questions must contain 1 to 10 items");
        }
        List<ClarificationQuestionV1> publicQuestions = new ArrayList<>();
        Set<String> fieldKeys = new HashSet<>();
        for (Object rawQuestion : questions) {
            Map<?, ?> question = requirePublicMap(rawQuestion, "question");
            String fieldKey = requirePublicText(question.get("fieldKey"), "fieldKey", 1, 64);
            if (!fieldKey.matches("[a-z][a-z0-9_]{0,63}") || !fieldKeys.add(fieldKey)) {
                throw new IllegalArgumentException(
                        "clarification fieldKey must be unique and use the supported format");
            }
            String prompt = requirePublicText(question.get("question"), "question", 1, 500);
            String context = optionalPublicText(question.get("context"), "context", 1_000);
            if (!(question.get("required") instanceof Boolean required)) {
                throw new IllegalArgumentException("clarification required must be boolean");
            }
            Object rawChoices = question.get("choices");
            if (!(rawChoices instanceof List<?> choices) || choices.size() > 5) {
                throw new IllegalArgumentException("clarification choices must contain at most 5 items");
            }
            List<String> publicChoices = choices.stream()
                    .map(choice -> requirePublicText(choice, "choice", 1, 200))
                    .toList();
            publicQuestions.add(new ClarificationQuestionV1(
                    fieldKey, prompt, context, required, publicChoices));
        }
        return Optional.of(new ClarificationRequestV1(
                schemaVersion, summary, publicQuestions));
    }

    private static Map<?, ?> requirePublicMap(Object value, String field) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException("clarification " + field + " must be an object");
    }

    private static String requirePublicText(
            Object value, String field, int minimumLength, int maximumLength) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("clarification " + field + " must be text");
        }
        String normalized = text.strip();
        if (normalized.length() < minimumLength
                || normalized.length() > maximumLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("clarification " + field + " is outside its public bounds");
        }
        return normalized;
    }

    private static String optionalPublicText(Object value, String field, int maximumLength) {
        if (value == null) {
            return null;
        }
        return requirePublicText(value, field, 0, maximumLength);
    }

    private static ExecutionFailure configurationFailure(Throwable failure) {
        return new ExecutionFailure(
                ExecutionFailureCategory.CAPABILITY_UNAVAILABLE,
                false,
                "The Personal Agent runtime configuration is unavailable.",
                Optional.of("AGENT_CONFIGURATION_UNAVAILABLE"));
    }

    private static ExecutionFailure agentStateUnavailableFailure() {
        return new ExecutionFailure(
                ExecutionFailureCategory.STATE_UNAVAILABLE,
                true,
                "The agent session state is temporarily unavailable.",
                Optional.of("AGENT_STATE_UNAVAILABLE"));
    }

    private static ExecutionFailure structuredOutputFailure(Throwable failure) {
        return new ExecutionFailure(
                ExecutionFailureCategory.MODEL_OUTPUT_INVALID,
                false,
                "The model returned an invalid structured response.",
                Optional.of("STRUCTURED_OUTPUT_INVALID"));
    }

    private static ExecutionFailure maxIterationsFailure() {
        return new ExecutionFailure(
                ExecutionFailureCategory.MODEL_OUTPUT_INVALID,
                false,
                "The agent reached its configured iteration limit.",
                Optional.of("MAX_ITERATIONS"));
    }

    private static ExecutionFailure allToolsDeniedFailure() {
        return new ExecutionFailure(
                ExecutionFailureCategory.AUTHORIZATION,
                false,
                "The requested tool actions were not authorized.",
                Optional.of("ALL_TOOLS_DENIED"));
    }

    private static ExecutionFailure incompleteStreamFailure() {
        return new ExecutionFailure(
                ExecutionFailureCategory.INTERNAL,
                false,
                "The agent execution stream ended unexpectedly.",
                Optional.of("EXECUTION_STREAM_INCOMPLETE"));
    }

    private static ExecutionFailure executionFailure(Throwable failure) {
        Throwable required = Objects.requireNonNull(failure, "failure");
        if (required instanceof AgentStateUnavailableException) {
            return agentStateUnavailableFailure();
        }
        if (required instanceof SafeModelExecutionException safe) {
            return safeModelFailure(safe.safeCode());
        }
        if (required instanceof PlatformExecutionSecurityException security) {
            return new ExecutionFailure(
                    ExecutionFailureCategory.AUTHORIZATION,
                    false,
                    "The Agent execution context is not authorized.",
                    Optional.of(security.safeCode()));
        }
        String type = required.getClass().getSimpleName().toLowerCase();
        String message = Optional.ofNullable(required.getMessage()).orElse("").toLowerCase();
        if (type.contains("rate") || message.contains("rate limit") || message.contains("429")) {
            return new ExecutionFailure(
                    ExecutionFailureCategory.MODEL_RATE_LIMITED,
                    true,
                    "The model is temporarily rate limited.",
                    Optional.of("MODEL_RATE_LIMITED"));
        }
        if (type.contains("timeout") || message.contains("timed out")) {
            return new ExecutionFailure(
                    ExecutionFailureCategory.TIMEOUT,
                    true,
                    "The agent execution timed out.",
                    Optional.of("AGENT_TIMEOUT"));
        }
        if (type.contains("state") || message.contains("state store")) {
            return new ExecutionFailure(
                    ExecutionFailureCategory.STATE_UNAVAILABLE,
                    true,
                    "The agent session state is temporarily unavailable.",
                    Optional.of("AGENT_STATE_UNAVAILABLE"));
        }
        if (type.contains("tool") || message.contains("tool execution")) {
            return new ExecutionFailure(
                    ExecutionFailureCategory.TOOL_FAILED,
                    true,
                    "An agent tool failed to complete.",
                    Optional.of("AGENT_TOOL_FAILED"));
        }
        return new ExecutionFailure(
                ExecutionFailureCategory.MODEL_UNAVAILABLE,
                true,
                "The model is temporarily unavailable.",
                Optional.of("MODEL_CALL_FAILED"));
    }

    private static ExecutionFailure safeModelFailure(String safeCode) {
        return switch (Objects.requireNonNull(safeCode, "safeCode")) {
            case "MODEL_RATE_LIMITED" -> new ExecutionFailure(
                    ExecutionFailureCategory.MODEL_RATE_LIMITED,
                    true,
                    "The model is temporarily rate limited.",
                    Optional.of(safeCode));
            case "MODEL_TIMEOUT" -> new ExecutionFailure(
                    ExecutionFailureCategory.TIMEOUT,
                    true,
                    "The model request timed out.",
                    Optional.of(safeCode));
            case "MODEL_AUTHENTICATION_FAILED" -> new ExecutionFailure(
                    ExecutionFailureCategory.CAPABILITY_UNAVAILABLE,
                    false,
                    "The model Provider configuration is unavailable.",
                    Optional.of(safeCode));
            case "MODEL_REQUEST_REJECTED" -> new ExecutionFailure(
                    ExecutionFailureCategory.MODEL_UNAVAILABLE,
                    false,
                    "The model request was rejected.",
                    Optional.of(safeCode));
            default -> new ExecutionFailure(
                    ExecutionFailureCategory.MODEL_UNAVAILABLE,
                    true,
                    "The model is temporarily unavailable.",
                    Optional.of(safeCode));
        };
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("AgentScopeNativeRuntime is closed");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        invocations.values().forEach(InvocationState::shutdown);
        invocations.clear();
        synchronized (terminalOrder) {
            terminalOrder.clear();
        }
        agentFactory.close();
    }

    private enum InvocationPhase {
        CREATED,
        RUNNING,
        INTERRUPTED,
        COMPLETED,
        CANCELED,
        FAILED
    }

    private enum CancelDecision {
        ACTIVE,
        INTERRUPTED_WAIT,
        ALREADY_TERMINAL
    }

    private static final class InvocationState {

        private final RuntimeInvocationId invocationId;
        private final AgentRuntimeSession runtimeSession;
        private final Optional<StructuredOutputSpec<?>> structuredOutput;
        private InvocationPhase phase = InvocationPhase.CREATED;
        private HarnessAgent agent;
        private RuntimeContext context;
        private PendingInterrupt pending;
        private UUID resumeRequestId;
        private String cancelReason;
        private Disposable running;
        private long nextSequence = 1;

        private InvocationState(
                RuntimeInvocationId invocationId,
                AgentRuntimeSession runtimeSession,
                Optional<StructuredOutputSpec<?>> structuredOutput) {
            this.invocationId = invocationId;
            this.runtimeSession = runtimeSession;
            this.structuredOutput = structuredOutput;
        }

        private static InvocationState initial(ConversationExecutionRequest request) {
            return new InvocationState(
                    request.invocationId(), request.runtimeSession(), request.structuredOutput());
        }

        private synchronized void bind(HarnessAgent agent, RuntimeContext context) {
            if (phase != InvocationPhase.CREATED) {
                throw new IllegalStateException("invocation is not ready for Agent binding");
            }
            this.agent = Objects.requireNonNull(agent, "agent");
            this.context = Objects.requireNonNull(context, "context");
        }

        private synchronized void beginSegment() {
            if (phase != InvocationPhase.CREATED && phase != InvocationPhase.RUNNING) {
                throw new IllegalStateException("invocation cannot start an execution segment");
            }
            phase = InvocationPhase.RUNNING;
            nextSequence = 1;
        }

        private synchronized PendingInterrupt previewResume(ConversationResumeRequest request) {
            validateResume(request);
            return pending;
        }

        private synchronized void beginResume(
                ConversationResumeRequest request,
                PendingInterrupt expectedPending,
                RuntimeContext refreshedContext) {
            validateResume(request);
            if (!pending.equals(Objects.requireNonNull(expectedPending, "expectedPending"))) {
                throw new IllegalStateException("the pending interrupt changed before resume");
            }
            resumeRequestId = request.resumeRequestId();
            context = Objects.requireNonNull(refreshedContext, "refreshedContext");
            phase = InvocationPhase.RUNNING;
            nextSequence = 1;
        }

        private synchronized void validateResume(ConversationResumeRequest request) {
            if (!belongsTo(request.runtimeSession())) {
                throw new IllegalArgumentException(
                        "resume Session does not own the invocation");
            }
            if (phase != InvocationPhase.INTERRUPTED || pending == null) {
                throw new IllegalStateException("invocation has no resumable interrupt");
            }
            if (!pending.token().equals(request.interruptToken())) {
                throw new IllegalArgumentException("interruptToken does not match the invocation");
            }
            if (pending.kind() == ExecutionInterruptKind.EXTERNAL_EXECUTION) {
                throw new IllegalStateException(
                        "external execution resume requires a typed external result");
            }
            if (resumeRequestId != null) {
                throw new IllegalStateException("the pending interrupt has already been resumed");
            }
        }

        private synchronized void attach(Disposable running) {
            this.running = Objects.requireNonNull(running, "running");
        }

        private synchronized long nextSequence() {
            return nextSequence++;
        }

        private synchronized ExecutionEventPayload completedPayload() {
            phase = InvocationPhase.COMPLETED;
            pending = null;
            return new ExecutionEventPayload.Completed();
        }

        private synchronized ExecutionEventPayload interruptedPayload(
                PendingInterrupt pendingInterrupt) {
            if (cancelReason != null) {
                return canceledPayload();
            }
            pending = Objects.requireNonNull(pendingInterrupt, "pendingInterrupt");
            // Resume idempotency belongs to one pending interrupt, not to the whole invocation.
            resumeRequestId = null;
            phase = InvocationPhase.INTERRUPTED;
            return new ExecutionEventPayload.Interrupted(
                    pending.token(),
                    pending.kind(),
                    pending.safePrompt(),
                    pending.clarification());
        }

        private synchronized ExecutionEventPayload interruptedOrCanceledPayload() {
            if (cancelReason != null) {
                return canceledPayload();
            }
            PendingInterrupt interrupt = PendingInterrupt.create(
                    Optional.empty(),
                    List.of(),
                    ExecutionInterruptKind.POLICY_CHECKPOINT);
            return interruptedPayload(interrupt);
        }

        private synchronized ExecutionEventPayload canceledPayload() {
            phase = InvocationPhase.CANCELED;
            pending = null;
            String reason = cancelReason != null ? cancelReason : "Invocation canceled";
            return new ExecutionEventPayload.Canceled(reason);
        }

        private synchronized ExecutionEventPayload failurePayload(ExecutionFailure failure) {
            if (cancelReason != null) {
                return canceledPayload();
            }
            phase = InvocationPhase.FAILED;
            pending = null;
            return new ExecutionEventPayload.Failed(failure);
        }

        private synchronized CancelDecision requestCancel(String reason) {
            if (isLogicalTerminal()) {
                return CancelDecision.ALREADY_TERMINAL;
            }
            cancelReason = reason;
            if (phase == InvocationPhase.INTERRUPTED) {
                phase = InvocationPhase.CANCELED;
                pending = null;
                return CancelDecision.INTERRUPTED_WAIT;
            }
            return CancelDecision.ACTIVE;
        }

        private synchronized boolean cancelRequested() {
            return cancelReason != null;
        }

        private synchronized boolean belongsTo(AgentRuntimeSession session) {
            AgentRuntimeSession required = Objects.requireNonNull(session, "runtimeSession");
            return runtimeSession.id().equals(required.id())
                    && runtimeSession.agentScopeKey().equals(required.agentScopeKey())
                    && runtimeSession.agentProfileId().equals(required.agentProfileId())
                    && runtimeSession.agentProfileVersion() == required.agentProfileVersion();
        }

        private synchronized boolean belongsTo(
                io.crewscope.application.execution.PlatformExecutionContext platformContext) {
            io.crewscope.application.execution.PlatformExecutionContext required =
                    Objects.requireNonNull(platformContext, "platformContext");
            return required.invocationId().equals(invocationId)
                    && required.runtimeSessionId().equals(runtimeSession.id())
                    && required.agentScopeSessionKey().equals(runtimeSession.agentScopeKey());
        }

        private synchronized void interruptAgent() {
            if (agent != null && context != null) {
                agent.getDelegate().interrupt(context);
            }
        }

        private synchronized boolean isLogicalTerminal() {
            return phase == InvocationPhase.COMPLETED
                    || phase == InvocationPhase.CANCELED
                    || phase == InvocationPhase.FAILED;
        }

        private synchronized boolean isSegmentTerminal() {
            return phase == InvocationPhase.INTERRUPTED || isLogicalTerminal();
        }

        private synchronized void shutdown() {
            if (!isLogicalTerminal()) {
                cancelReason = "Runtime shutdown";
                interruptAgent();
            }
            if (running != null) {
                running.dispose();
            }
        }

        private RuntimeInvocationId invocationId() {
            return invocationId;
        }

        private Optional<StructuredOutputSpec<?>> structuredOutput() {
            return structuredOutput;
        }

        private synchronized HarnessAgent agent() {
            return Objects.requireNonNull(agent, "agent");
        }

        private synchronized RuntimeContext context() {
            return Objects.requireNonNull(context, "context");
        }
    }

    private record PendingInterrupt(
            ExecutionInterruptToken token,
            Optional<String> replyId,
            List<ToolUseBlock> toolCalls,
            ExecutionInterruptKind kind,
            String safePrompt,
            Optional<ClarificationRequestV1> clarification) {

        private PendingInterrupt {
            token = Objects.requireNonNull(token, "token");
            replyId = Objects.requireNonNull(replyId, "replyId");
            toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
            kind = Objects.requireNonNull(kind, "kind");
            safePrompt = Objects.requireNonNull(safePrompt, "safePrompt");
            clarification = Objects.requireNonNull(clarification, "clarification");
        }

        private static PendingInterrupt create(
                Optional<String> replyId,
                List<ToolUseBlock> toolCalls,
                ExecutionInterruptKind kind) {
            return new PendingInterrupt(
                    new ExecutionInterruptToken(UUID.randomUUID().toString()),
                    replyId,
                    toolCalls,
                    kind,
                    AgentScopeNativeRuntime.safePrompt(kind),
                    publicClarification(toolCalls, kind));
        }
    }

    private static final class SegmentCapture {

        private Optional<String> replyId = Optional.empty();
        private List<ToolUseBlock> toolCalls = List.of();
        private ExecutionInterruptKind kind = ExecutionInterruptKind.POLICY_CHECKPOINT;
        private boolean maxIterations;

        private void userConfirmation(RequireUserConfirmEvent confirmation) {
            replyId = Optional.ofNullable(confirmation.getReplyId());
            toolCalls = List.copyOf(confirmation.getToolCalls());
            kind = interruptKind(toolCalls, false);
        }

        private void externalExecution(RequireExternalExecutionEvent external) {
            replyId = Optional.ofNullable(external.getReplyId());
            toolCalls = List.copyOf(external.getToolCalls());
            kind = ExecutionInterruptKind.EXTERNAL_EXECUTION;
        }

        private void stop(RequestStopEvent stop) {
            if (stop.getGenerateReason() == GenerateReason.PERMISSION_ASKING
                    && kind == ExecutionInterruptKind.POLICY_CHECKPOINT) {
                kind = interruptKind(toolCalls, false);
            }
        }

        private PendingInterrupt pending(Msg result) {
            if (toolCalls.isEmpty()) {
                List<ToolUseBlock> resultTools = result.getContentBlocks(ToolUseBlock.class).stream()
                        .filter(tool -> tool.getState() == ToolCallState.ASKING
                                || tool.getState() == ToolCallState.PENDING)
                        .toList();
                if (!resultTools.isEmpty()) {
                    toolCalls = resultTools;
                    kind = interruptKind(toolCalls, false);
                }
            }
            return PendingInterrupt.create(replyId, toolCalls, kind);
        }
    }
}
