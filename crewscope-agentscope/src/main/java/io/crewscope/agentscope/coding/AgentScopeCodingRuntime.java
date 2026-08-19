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
import io.crewscope.agentscope.StrictStructuredOutputDecoder;
import io.crewscope.application.coding.output.CodeChangeResultV1;
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
        JsonNode schema = JsonUtils.getJsonCodec().convertValue(
                CodingStructuredOutputSpecs.CODE_CHANGE_RESULT
                        .strictJsonSchema()
                        .orElseThrow(),
                JsonNode.class);
        return agent.call(List.of(new UserMessage(request.instruction())), schema, context)
                .map(result -> completedResult(agent, key, result));
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
            HarnessAgent agent, AgentScopeSessionKey key, Msg result) {
        if (!result.hasStructuredData()) {
            throw new IllegalStateException(
                    "Coding Specialist did not produce its required structured output");
        }
        CodingSpecialistToolSurface.requireRuntimeToolkit(agent.getToolkit(), true);
        Object decoded = StrictStructuredOutputDecoder.decode(
                result.getStructuredData(false),
                CodingStructuredOutputSpecs.CODE_CHANGE_RESULT);
        CodeChangeResultV1 output = (CodeChangeResultV1) decoded;
        return new CodingSpecialistRunResult(output, stateSnapshot(agent, key));
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
}
