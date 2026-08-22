package io.crewscope.agentscope.coding;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.Task;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.ReActAgent;
import io.crewscope.agentscope.StrictStructuredOutputDecoder;
import io.crewscope.application.coding.output.CodeChangeResultV1;
import io.crewscope.application.coding.output.CodingDeliverySummaryV1;
import io.crewscope.application.coding.output.CodingStructuredOutputSpecs;
import io.crewscope.domain.coding.CodingCheckpointTodo;
import io.crewscope.domain.coding.CodingCheckpointWorkState;
import io.crewscope.domain.coding.CodingTodoStatus;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import reactor.core.publisher.Mono;

/** Executes one complete Coding Specialist loop without owning Task/Worker state transitions. */
public final class AgentScopeCodingRuntime {

    private static final String STRUCTURED_OUTPUT_RECOVERY_INSTRUCTION = """
            The Coding Agent already completed the repository change and verification. Produce its
            delivery result now. Call generate_response exactly once; do not answer with text.

            Summarize the requested change from this task:
            %s

            Return schemaVersion=1. changeSummary must contain at least one concrete item.
            limitations and risks must be arrays and may be empty.
            """;
    private static final String RUNTIME_RECOVERY_INSTRUCTION = """
            The previous Coding turn could not be accepted. Continue this same bounded task from
            the current Agent state and repository Worktree. Inspect current repository status,
            complete any missing change and verification, update Plan and Todo state, then call
            generate_response exactly once. Do not repeat work that is already complete.

            Task:
            %s
            """;
    private static final String PLACEHOLDER_UUID = "00000000-0000-4000-8000-000000000000";
    private static final String PLACEHOLDER_SHA256 = "0".repeat(64);

    private final CodingSpecialistFactory factory;
    private final ConcurrentMap<AgentScopeSessionKey, HarnessAgent> activeCalls =
            new ConcurrentHashMap<>();

    public AgentScopeCodingRuntime(CodingSpecialistFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /**
     * Runs analysis, planning, controlled mutation, verification and final structured output.
     * The returned AgentState is a same-call safe point; M4-I12 persists and fences it.
     */
    public Mono<CodingSpecialistRunResult> execute(CodingSpecialistRequest request) {
        CodingSpecialistRequest required = Objects.requireNonNull(request, "request");
        AgentScopeSessionKey key = required.runtimeSession().agentScopeKey();
        return Mono.using(
                () -> register(required, key),
                agent -> execute(agent, required),
                agent -> {
                    activeCalls.remove(key, agent);
                    agent.close();
                },
                true);
    }

    /** Signals exactly one in-flight Specialist call; unrelated Sessions remain untouched. */
    public boolean interrupt(TaskAgentRuntimeSession runtimeSession) {
        TaskAgentRuntimeSession session = Objects.requireNonNull(runtimeSession, "runtimeSession");
        AgentScopeSessionKey key = session.agentScopeKey();
        HarnessAgent agent = activeCalls.get(key);
        if (agent == null) {
            return false;
        }
        agent.getDelegate().interrupt(key.userId(), key.sessionId());
        return true;
    }

    /** Restores trusted durable bytes into the exact AgentScope state slot before Resume. */
    public void restore(TaskAgentRuntimeSession runtimeSession, String agentStateJson) {
        TaskAgentRuntimeSession session = Objects.requireNonNull(runtimeSession, "runtimeSession");
        AgentScopeSessionKey key = session.agentScopeKey();
        if (activeCalls.containsKey(key)) {
            throw new IllegalStateException("Cannot restore an active Coding Specialist Session");
        }
        AgentState state = AgentState.fromJsonString(
                Objects.requireNonNull(agentStateJson, "agentStateJson"));
        requireStateIdentity(key, state);
        factory.stateStore().save(key.userId(), key.sessionId(), "agent_state", state);
    }

    /** Captures a finite safe point after interruption without starting another model turn. */
    public CodingSpecialistStateSnapshot snapshot(CodingSpecialistRequest request) {
        CodingSpecialistRequest required = Objects.requireNonNull(request, "request");
        AgentScopeSessionKey key = required.runtimeSession().agentScopeKey();
        if (activeCalls.containsKey(key)) {
            throw new IllegalStateException("Coding Specialist call has not reached a safe point");
        }
        try (HarnessAgent agent = factory.create(
                required.runtimeSession(), required.toolkit())) {
            return stateSnapshot(agent, key);
        }
    }

    private Mono<CodingSpecialistRunResult> execute(
            HarnessAgent agent, CodingSpecialistRequest request) {
        AgentScopeSessionKey key = request.runtimeSession().agentScopeKey();
        RuntimeContext context = RuntimeContext.builder()
                .userId(key.userId())
                .sessionId(key.sessionId())
                .build();
        CodingSpecialistTelemetryAccumulator telemetry =
                new CodingSpecialistTelemetryAccumulator();
        context.put(CodingSpecialistTelemetryAccumulator.class, telemetry);
        JsonNode schema = JsonUtils.getJsonCodec().convertValue(
                CodingStructuredOutputSpecs.CODING_DELIVERY_SUMMARY
                        .strictJsonSchema()
                        .orElseThrow(),
                JsonNode.class);
        enterInitialPlanMode(agent, context, key);
        return executeOnce(agent, request, schema, context, telemetry, key)
                // One logical recovery turn absorbs malformed provider output, an invalid native
                // safe point without creating an unbounded retry loop. A malformed delivery
                // summary is recovered inside callForStructuredResult and must never repeat
                // repository mutation or tests. Provider transport retries remain AgentScope's.
                .onErrorResume(firstFailure -> firstFailure instanceof StructuredDeliveryException
                        ? Mono.error(firstFailure)
                        : executeOnce(
                                agent,
                                new CodingSpecialistRequest(
                                        request.runtimeSession(),
                                        request.toolkit(),
                                        RUNTIME_RECOVERY_INSTRUCTION.formatted(
                                                request.instruction())),
                                schema,
                                context,
                                telemetry,
                                key))
                .onErrorMap(failure -> failure instanceof CodingSpecialistExecutionException
                        ? failure
                        : new CodingSpecialistExecutionException(
                                telemetry.snapshot(), failure));
    }

    private static void enterInitialPlanMode(
            HarnessAgent agent, RuntimeContext context, AgentScopeSessionKey key) {
        AgentState state = agent.getDelegate().getAgentState(key.userId(), key.sessionId());
        String existingPlan = state.getPlanModeContext().getCurrentPlanFile();
        if (existingPlan == null || existingPlan.isBlank()) {
            // AgentScope Plan Mode is the deterministic mutation gate: the Specialist may inspect
            // first, but coding tools remain blocked until it writes and exits an approved plan.
            // Restored Sessions with a durable plan keep their exact prior BUILD/PLAN state.
            agent.enterPlanMode(context);
        }
    }

    private Mono<CodingSpecialistRunResult> executeOnce(
            HarnessAgent agent,
            CodingSpecialistRequest request,
            JsonNode schema,
            RuntimeContext context,
            CodingSpecialistTelemetryAccumulator telemetry,
            AgentScopeSessionKey key) {
        return callForStructuredResult(agent, request, schema, context, telemetry)
                .map(result -> completedResult(agent, key, result, telemetry));
    }

    private Mono<Msg> callForStructuredResult(
            HarnessAgent agent,
            CodingSpecialistRequest request,
            JsonNode schema,
            RuntimeContext context,
            CodingSpecialistTelemetryAccumulator telemetry) {
        return agent.call(List.of(new UserMessage(request.instruction())), schema, context)
                .flatMap(result -> {
                    if (validStructuredDelivery(result)) {
                        return Mono.just(result);
                    }
                    telemetry.requireStructuredOutput();
                    return callStructuredResultAgent(request, schema, context)
                            .flatMap(recovered -> validStructuredDelivery(recovered)
                                    ? Mono.just(recovered)
                                    : Mono.error(new StructuredDeliveryException()));
                });
    }

    private static boolean validStructuredDelivery(Msg result) {
        if (!result.hasStructuredData()) {
            return false;
        }
        try {
            StrictStructuredOutputDecoder.decode(
                    result.getStructuredData(false),
                    CodingStructuredOutputSpecs.CODING_DELIVERY_SUMMARY);
            return true;
        } catch (RuntimeException invalidDelivery) {
            // Provider output is untrusted. Keep its content out of logs and trigger the bounded
            // native structured-output recovery path instead of retrying repository work.
            return false;
        }
    }

    private Mono<Msg> callStructuredResultAgent(
            CodingSpecialistRequest request, JsonNode schema, RuntimeContext context) {
        return Mono.using(
                () -> factory.createStructuredResultAgent(request.runtimeSession()),
                resultAgent -> resultAgent.call(
                        List.of(new UserMessage(STRUCTURED_OUTPUT_RECOVERY_INSTRUCTION.formatted(
                                request.instruction()))),
                        schema,
                        context),
                ReActAgent::close,
                true);
    }

    private HarnessAgent register(
            CodingSpecialistRequest request, AgentScopeSessionKey key) {
        HarnessAgent agent = factory.create(request.runtimeSession(), request.toolkit());
        HarnessAgent existing = activeCalls.putIfAbsent(key, agent);
        if (existing != null) {
            agent.close();
            throw new IllegalStateException(
                    "Coding Specialist Session already has an active call");
        }
        return agent;
    }

    private static CodingSpecialistRunResult completedResult(
            HarnessAgent agent,
            AgentScopeSessionKey key,
            Msg result,
            CodingSpecialistTelemetryAccumulator telemetry) {
        CodingSpecialistToolSurface.requireRuntimeToolkit(agent.getToolkit(), true);
        CodingDeliverySummaryV1 summary = (CodingDeliverySummaryV1) StrictStructuredOutputDecoder.decode(
                result.getStructuredData(false),
                CodingStructuredOutputSpecs.CODING_DELIVERY_SUMMARY);
        // Authority coordinates are intentionally absent from the model contract. The Step
        // runtime replaces these shape-valid placeholders with Git/Workspace/Test facts.
        CodeChangeResultV1 output = new CodeChangeResultV1(
                CodeChangeResultV1.SCHEMA_VERSION,
                PLACEHOLDER_UUID,
                PLACEHOLDER_SHA256,
                PLACEHOLDER_UUID,
                1,
                PLACEHOLDER_SHA256,
                PLACEHOLDER_SHA256,
                PLACEHOLDER_UUID,
                PLACEHOLDER_SHA256,
                PLACEHOLDER_UUID,
                PLACEHOLDER_SHA256,
                summary.changeSummary(),
                summary.limitations(),
                summary.risks());
        return new CodingSpecialistRunResult(
                output, stateSnapshot(agent, key), telemetry.snapshot());
    }

    private static CodingSpecialistStateSnapshot stateSnapshot(
            HarnessAgent agent, AgentScopeSessionKey key) {
        AgentStateStore store = Objects.requireNonNull(
                agent.getStateStore(), "Coding AgentStateStore");
        AgentState state = store.get(key.userId(), key.sessionId(), "agent_state", AgentState.class)
                .orElseThrow(() -> new IllegalStateException(
                        "Coding AgentState is absent at the safe point"));
        requireStateIdentity(key, state);
        return new CodingSpecialistStateSnapshot(
                agent.getName(),
                key.userId(),
                key.sessionId(),
                state.toJson(),
                workState(agent, key, state));
    }

    private static CodingCheckpointWorkState workState(
            HarnessAgent agent, AgentScopeSessionKey key, AgentState state) {
        String planPath = state.getPlanModeContext().getCurrentPlanFile();
        if (planPath == null || planPath.isBlank()) {
            throw new IllegalStateException("Coding Specialist did not persist a recovery Plan");
        }
        RuntimeContext context = RuntimeContext.builder()
                .userId(key.userId())
                .sessionId(key.sessionId())
                .build();
        ReadResult plan = agent.workspaceFor(key.userId(), key.sessionId())
                .getFilesystem()
                .read(context, planPath, 0, 0);
        if (!plan.isSuccess()
                || plan.fileData() == null
                || !"utf-8".equalsIgnoreCase(plan.fileData().encoding())) {
            throw new IllegalStateException("Coding Specialist recovery Plan is unavailable");
        }
        List<CodingCheckpointTodo> todos = state.getTasksContext().getTasks().stream()
                .map(AgentScopeCodingRuntime::todo)
                .toList();
        return new CodingCheckpointWorkState(plan.fileData().content(), todos);
    }

    private static CodingCheckpointTodo todo(Task task) {
        CodingTodoStatus status = switch (task.getState()) {
            case PENDING -> CodingTodoStatus.PENDING;
            case IN_PROGRESS -> CodingTodoStatus.IN_PROGRESS;
            case COMPLETED -> CodingTodoStatus.COMPLETED;
        };
        return new CodingCheckpointTodo(task.getId(), status, task.getSubject());
    }

    private static void requireStateIdentity(AgentScopeSessionKey key, AgentState state) {
        if (!key.userId().equals(state.getUserId())
                || !key.sessionId().equals(state.getSessionId())) {
            throw new IllegalStateException("Coding AgentState crossed its Session boundary");
        }
    }

    /** Internal marker that prevents delivery-shape failures from replaying Coding tools. */
    private static final class StructuredDeliveryException extends RuntimeException {

        private StructuredDeliveryException() {
            super("Coding Specialist did not produce a valid structured delivery");
        }
    }
}
