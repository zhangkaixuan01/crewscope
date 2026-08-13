package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.Task;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.task.AgentScopeTaskPlanningSnapshotMapper;
import io.crewscope.application.execution.RuntimeCapability;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/** AgentScope 2.0.0 evidence for the M3 Task Orchestrator planning and recovery boundary. */
@Tag("integration")
class HarnessAgentM3S02TaskOrchestratorIntegrationTest {

    private static final String USER_ID = "task-executor-m3-s02";
    private static final String SESSION_ID = "task-execution-m3-s02";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String PLAN =
            """
            # Goal

            Deliver a controlled fixture task.

            ## Steps

            1. Inspect the input.
            2. Produce the fixture result.
            3. Verify the result.
            """;

    @TempDir Path workspace;

    @Test
    void restoresPlanTodoAndPendingExitInANewAgentInstance() throws Exception {
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        ControlledMutationTool mutation = new ControlledMutationTool();
        RuntimeContext context = taskContext();
        ScriptedModel planningModel = new ScriptedModel(
                toolResponse("plan-write", "plan_write", Map.of("content", PLAN)),
                toolResponse("forbidden-change", mutation.getName(), Map.of("value", "unsafe")),
                toolResponse("todo-seed", "todo_write", Map.of("todos", initialTodos())),
                toolResponse("plan-exit", "plan_exit", Map.of("summary", "Fixture plan ready")));

        List<AgentEvent> interrupted;
        try (HarnessAgent planningAgent =
                taskAgent(planningModel, stateStore, mutation)) {
            planningAgent.enterPlanMode(context);
            interrupted = planningAgent
                    .streamEvents(new UserMessage("Plan the controlled task"), context)
                    .collectList()
                    .block(TIMEOUT);
        }

        assertNotNull(interrupted);
        assertEquals(0, mutation.executionCount());
        RequireUserConfirmEvent confirmation = interrupted.stream()
                .filter(RequireUserConfirmEvent.class::isInstance)
                .map(RequireUserConfirmEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(indexOf(interrupted, RequireUserConfirmEvent.class)
                < indexOf(interrupted, RequestStopEvent.class));
        Msg interruptedResult = resultOf(interrupted);
        assertEquals(GenerateReason.PERMISSION_ASKING, interruptedResult.getGenerateReason());
        ToolUseBlock pendingExit = onlyPendingTool(interruptedResult, "plan_exit");

        AgentState saved = savedState(stateStore);
        List<ToolUseBlock> savedToolUses = saved.getContext().stream()
                .flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                .toList();
        assertTrue(
                savedToolUses.stream().anyMatch(tool -> "plan_exit".equals(tool.getName())
                        && tool.getState() == ToolCallState.ASKING),
                () -> "Saved tool uses: " + savedToolUses.stream()
                        .map(tool -> tool.getName() + ":" + tool.getState())
                        .toList());
        Msg lastSaved = saved.getContext().get(saved.getContext().size() - 1);
        assertTrue(
                lastSaved.getContentBlocks(ToolUseBlock.class).stream()
                        .anyMatch(tool -> "plan_exit".equals(tool.getName())),
                () -> "Last saved message=" + lastSaved.getRole() + ":" + lastSaved.getContent());
        assertTrue(saved.getPlanModeContext().isPlanActive());
        assertEquals("plans/PLAN.md", saved.getPlanModeContext().getCurrentPlanFile());
        assertEquals(3, saved.getTasksContext().getTasks().size());
        assertEquals(
                "Inspect the input", saved.getTasksContext().getTasks().get(0).getSubject());
        // Harness local workspace defaults to USER isolation, so logical plans/PLAN.md is stored
        // beneath the RuntimeContext user namespace.
        Path physicalPlan = workspace.resolve(USER_ID).resolve("plans/PLAN.md");
        assertTrue(Files.readString(physicalPlan).contains("fixture result"));
        assertTrue(saved.getContext().stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .anyMatch(result -> "controlled_mutation".equals(result.getName())
                        && result.getState() == ToolResultState.DENIED));

        ScriptedModel resumedModel =
                new ScriptedModel("Plan approved; controlled execution may start.");
        try (HarnessAgent resumedAgent =
                taskAgent(resumedModel, stateStore, mutation)) {
            assertTrue(resumedAgent.isPlanModeActive(context));

            Msg resumed = resumedAgent.call(approve(pendingExit), context).block(TIMEOUT);

            assertNotNull(resumed);
            assertFalse(
                    resumedAgent.isPlanModeActive(context),
                    () -> "Resume result=" + resumed.getTextContent() + ", plan_exit results="
                            + savedState(stateStore).getContext().stream()
                                    .flatMap(message -> message
                                            .getContentBlocks(ToolResultBlock.class)
                                            .stream())
                                    .filter(result -> "plan_exit".equals(result.getName()))
                                    .map(result -> result.getState() + ":" + result.getOutput())
                                    .toList());
            assertTrue(resumed.getTextContent().contains("controlled execution may start"));
        }

        AgentState resumedState = savedState(stateStore);
        assertFalse(resumedState.getPlanModeContext().isPlanActive());
        assertEquals(3, resumedState.getTasksContext().getTasks().size());
        assertTrue(resumedState.getContext().stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .anyMatch(result -> "plan_exit".equals(result.getName())));
        assertEquals(1, confirmation.getToolCalls().size());
    }

    @Test
    void mapsPlanAndTodoAsCandidatesWithoutPublishingDomainFacts() throws Exception {
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        RuntimeContext context = taskContext();
        ScriptedModel model = new ScriptedModel(
                toolResponse("todo-seed", "todo_write", Map.of("todos", initialTodos())),
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("Todo snapshot ready").build()))
                        .usage(new ChatUsage(10, 4, 0.01))
                        .finishReason("stop")
                        .build());
        try (HarnessAgent agent = taskAgent(model, stateStore, new ControlledMutationTool())) {
            agent.enterPlanMode(context);
            assertNotNull(agent.call(new UserMessage("Track the plan"), context).block(TIMEOUT));
        }

        AgentScopeTaskPlanningSnapshotMapper.TaskPlanningSnapshot snapshot =
                new AgentScopeTaskPlanningSnapshotMapper()
                        .map(savedState(stateStore), Optional.of("\r\n" + PLAN + "\r\n"));

        assertTrue(snapshot.planModeActive());
        assertEquals(3, snapshot.todos().size());
        assertEquals(
                AgentScopeTaskPlanningSnapshotMapper.TodoStatus.IN_PROGRESS,
                snapshot.todos().get(0).status());
        assertEquals(Optional.of("high"), snapshot.todos().get(0).priority());
        AgentScopeTaskPlanningSnapshotMapper.ProposedPlan proposed =
                snapshot.proposedPlan().orElseThrow();
        assertEquals(Optional.of("plans/PLAN.md"), proposed.sourcePath());
        assertEquals(64, proposed.sha256().length());
        assertFalse(proposed.markdown().contains("\r"));

        AgentScopeTaskPlanningSnapshotMapper mapper = new AgentScopeTaskPlanningSnapshotMapper();
        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.map(savedState(stateStore), Optional.of(" \r\n\t ")));
    }

    @Test
    void keepsUnwiredTaskCapabilitiesOutOfTheM2RuntimeProfile() {
        assertFalse(AgentScopeRuntimeProfile.capabilities().supports(RuntimeCapability.PLAN));
        assertFalse(AgentScopeRuntimeProfile.capabilities().supports(RuntimeCapability.SANDBOX));
        assertFalse(AgentScopeRuntimeProfile.capabilities().supports(RuntimeCapability.SUBAGENT));
        assertFalse(AgentScopeRuntimeProfile.capabilities()
                .supports(RuntimeCapability.EXTERNAL_TOOL));
    }

    @Test
    void rejectsAmbiguousTodoProgressAtTheCrewScopeBoundary() {
        AgentState state = AgentState.builder().build();
        state.getTasksContext().tasksMutable().addAll(List.of(
                runtimeTodo("First active Todo", Task.State.IN_PROGRESS),
                runtimeTodo("Second active Todo", Task.State.IN_PROGRESS)));

        AgentScopeTaskPlanningSnapshotMapper mapper = new AgentScopeTaskPlanningSnapshotMapper();
        assertThrows(IllegalArgumentException.class, () -> mapper.map(state, Optional.empty()));
    }

    @Test
    void rejectsDirectConstructionThatBypassesPlanningSnapshotInvariants() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentScopeTaskPlanningSnapshotMapper.ProposedPlan(
                        Optional.of("plans/PLAN.md"), PLAN, "0".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentScopeTaskPlanningSnapshotMapper.ProposedPlan(
                        Optional.of("plans/PLAN.md\nforeign"), PLAN, "0".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentScopeTaskPlanningSnapshotMapper.TodoSnapshotItem(
                        " ",
                        AgentScopeTaskPlanningSnapshotMapper.TodoStatus.PENDING,
                        Optional.empty()));
        AgentScopeTaskPlanningSnapshotMapper.TodoSnapshotItem todo =
                new AgentScopeTaskPlanningSnapshotMapper.TodoSnapshotItem(
                        "bounded",
                        AgentScopeTaskPlanningSnapshotMapper.TodoStatus.PENDING,
                        Optional.empty());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentScopeTaskPlanningSnapshotMapper.TaskPlanningSnapshot(
                        false, Optional.empty(), java.util.Collections.nCopies(101, todo)));
    }

    private HarnessAgent taskAgent(
            ScriptedModel model,
            InMemoryAgentStateStore stateStore,
            ControlledMutationTool mutation) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(mutation);
        return HarnessAgent.builder()
                // AgentScope finds resumable ToolUseBlocks by agent name, so name and agentId are
                // both stable parts of the versioned Task Agent runtime identity.
                .name("crewscope-m3-s02-task-agent")
                .agentId("crewscope-m3-s02-task-agent")
                .description("CrewScope M3-S02 deterministic Task Agent")
                .sysPrompt("Plan the controlled task, maintain Todo state, then request approval.")
                .model(model)
                .toolkit(toolkit)
                .workspace(workspace)
                .stateStore(stateStore)
                .enablePlanMode()
                .enableTaskList()
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableToolsConfig()
                .disableCompaction()
                .enableAgentTracingLog(false)
                .maxIters(10)
                .build();
    }

    private static RuntimeContext taskContext() {
        return RuntimeContext.builder().userId(USER_ID).sessionId(SESSION_ID).build();
    }

    private static AgentState savedState(InMemoryAgentStateStore stateStore) {
        return stateStore.get(USER_ID, SESSION_ID, "agent_state", AgentState.class).orElseThrow();
    }

    private static List<Map<String, Object>> initialTodos() {
        return List.of(
                todo("Inspect the input", "in_progress", "high"),
                todo("Produce the fixture result", "pending", "medium"),
                todo("Verify the result", "pending", "low"));
    }

    private static Map<String, Object> todo(String content, String status, String priority) {
        Map<String, Object> todo = new LinkedHashMap<>();
        todo.put("content", content);
        todo.put("status", status);
        todo.put("priority", priority);
        return todo;
    }

    private static Task runtimeTodo(String content, Task.State state) {
        return Task.builder()
                .subject(content)
                .description(content)
                .state(state)
                .build();
    }

    private static ChatResponse toolResponse(
            String id, String name, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(List.of(ToolUseBlock.builder()
                        .id(id)
                        .name(name)
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build()))
                .usage(new ChatUsage(10, 4, 0.01))
                .finishReason("tool_calls")
                .build();
    }

    private static Msg approve(ToolUseBlock pending) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Msg.METADATA_CONFIRM_RESULTS, List.of(new ConfirmResult(true, pending)));
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("[plan-approved]")
                .metadata(metadata)
                .build();
    }

    private static ToolUseBlock onlyPendingTool(Msg result, String expectedName) {
        List<ToolUseBlock> pending = result.getContentBlocks(ToolUseBlock.class).stream()
                .filter(tool -> tool.getState() == ToolCallState.ASKING)
                .toList();
        assertEquals(1, pending.size());
        assertEquals(expectedName, pending.get(0).getName());
        return pending.get(0);
    }

    private static Msg resultOf(List<AgentEvent> events) {
        return events.stream()
                .filter(AgentResultEvent.class::isInstance)
                .map(AgentResultEvent.class::cast)
                .map(AgentResultEvent::getResult)
                .findFirst()
                .orElseThrow();
    }

    private static int indexOf(List<AgentEvent> events, Class<?> type) {
        for (int index = 0; index < events.size(); index++) {
            if (type.isInstance(events.get(index))) {
                return index;
            }
        }
        return -1;
    }

    /** Mutating fixture that must never execute while Plan Mode is active. */
    private static final class ControlledMutationTool extends ToolBase {

        private final AtomicInteger executions = new AtomicInteger();

        private ControlledMutationTool() {
            super(ToolBase.builder()
                    .name("controlled_mutation")
                    .description("M3-S02 mutating fixture")
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of("value", Map.of("type", "string")),
                            "required", List.of("value")))
                    .readOnly(false)
                    .concurrencySafe(false));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executions.incrementAndGet();
            return Mono.just(ToolResultBlock.text("mutated"));
        }

        @Override
        public Mono<io.agentscope.core.permission.PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(
                    io.agentscope.core.permission.PermissionDecision.allow("M3-S02 fixture"));
        }

        int executionCount() {
            return executions.get();
        }
    }
}
