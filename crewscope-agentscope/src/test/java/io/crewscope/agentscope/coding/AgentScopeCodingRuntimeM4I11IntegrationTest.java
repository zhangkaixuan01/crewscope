package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.coding.AgentScopeCodingRuntime;
import io.crewscope.agentscope.coding.CodingSpecialistConfiguration;
import io.crewscope.agentscope.coding.CodingSpecialistConfigurationSource;
import io.crewscope.agentscope.coding.CodingSpecialistFactory;
import io.crewscope.agentscope.coding.CodingSpecialistRequest;
import io.crewscope.agentscope.coding.CodingSpecialistRunResult;
import io.crewscope.agentscope.coding.CodingSpecialistSkillBundle;
import io.crewscope.agentscope.coding.CodingSpecialistToolSurface;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.agent.AgentReasoningMode;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import io.crewscope.domain.workspace.AgentProfileId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentScopeCodingRuntimeM4I11IntegrationTest {

    @TempDir Path runtimeRoot;

    @Test
    void controlledModelUsesSkillPlansChangesTwoFilesTestsSelfChecksAndReturnsStrictOutput()
            throws IOException {
        ControlledCodingTools tools = new ControlledCodingTools();
        Toolkit toolkit = toolkit(tools);
        ScriptedModel primary = new ScriptedModel(
                toolResponse(
                        "skill",
                        CodingSpecialistToolSurface.SKILL_LOAD_TOOL,
                        Map.of(
                                "skillId", CodingSpecialistSkillBundle.SKILL_ID,
                                "path", "SKILL.md")),
                toolResponse("todos", "todo_write", Map.of("todos", todos("in_progress"))),
                toolResponse("plan-enter", "plan_enter", Map.of()),
                toolResponse(
                        "read-one",
                        "repository_read",
                        Map.of("path", "src/main/java/example/One.java")),
                toolResponse(
                        "read-two",
                        "repository_read",
                        Map.of("path", "src/main/java/example/Two.java")),
                toolResponse(
                        "plan-write",
                        "plan_write",
                        Map.of("content", "1. Inspect both files\n2. Edit both files\n3. Test and inspect diff")),
                toolResponse(
                        "plan-exit",
                        "plan_exit",
                        Map.of("summary", "Apply the two bounded changes and verify them")),
                toolResponse(
                        "edit-one",
                        "coding_edit",
                        Map.of(
                                "path", "src/main/java/example/One.java",
                                "old_text", "before",
                                "new_text", "after",
                                "replace_all", false)),
                toolResponse(
                        "edit-two",
                        "coding_edit",
                        Map.of(
                                "path", "src/main/java/example/Two.java",
                                "old_text", "before",
                                "new_text", "after",
                                "replace_all", false)),
                toolResponse(
                        "test",
                        CodingSpecialistToolSurface.COMMAND_TOOL,
                        Map.of("command_kind", "TEST")),
                toolResponse("self-check", "repository_git_diff", Map.of()),
                toolResponse("todos-done", "todo_write", Map.of("todos", todos("completed"))),
                structuredResponse(validResult()));
        ScriptedModel compaction = repeatedModel("M4-I11 compacted coding context", 20);
        AgentScopeCodingRuntime runtime = runtime(primary, compaction, 8, 512, 64);

        CodingSpecialistRunResult result = runtime.execute(new CodingSpecialistRequest(
                        specialistSession(), toolkit, "Analyze, plan, implement, test and self-check."))
                .block(Duration.ofSeconds(10));

        assertEquals(List.of("after", "after"), List.copyOf(tools.files.values()));
        assertTrue(tools.tested);
        assertTrue(tools.diffInspected);
        assertEquals(List.of("Updated both bounded fixture files"), result.output().changeSummary());
        assertTrue(result.stateSnapshot().agentStateJson().contains("completed"));
        assertTrue(result.stateSnapshot().workState().planMarkdown()
                .contains("Inspect both files"));
        assertEquals(3, result.stateSnapshot().workState().todos().size());
        assertTrue(result.stateSnapshot().workState().todos().stream()
                .allMatch(todo -> todo.status()
                        == io.crewscope.domain.coding.CodingTodoStatus.COMPLETED));
        assertTrue(result.telemetry().modelCalls() >= primary.callCount());
        assertTrue(result.telemetry().toolCalls() >= 12);
        assertTrue(result.telemetry().modelUsages().stream()
                .mapToLong(usage -> usage.inputTokens() + usage.outputTokens())
                .sum() > 0);
        assertFalse(result.stateSnapshot().toString().contains("agent_state"));
        assertTrue(compaction.callCount() > 0);
        try (java.util.stream.Stream<Path> paths = Files.walk(runtimeRoot)) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().equals("test")));
        }
        assertTrue(primary.request(0).stream()
                .map(Msg::getTextContent)
                .filter(java.util.Objects::nonNull)
                .anyMatch(text -> text.contains(CodingSpecialistSkillBundle.SKILL_NAME)));
        assertTrue(primary.request(0).stream()
                .map(Msg::getTextContent)
                .filter(java.util.Objects::nonNull)
                .anyMatch(text -> text.contains("PLAN MODE is active")));
        assertEquals(CodingSpecialistToolSurface.controlledTools(), toolkit.getToolNames());
    }

    @Test
    void rejectsAnyMissingExtraOrRawToolBeforeTheModelRuns() {
        ControlledCodingTools tools = new ControlledCodingTools();
        Toolkit missing = toolkit(tools);
        missing.removeTool("coding_delete");
        Toolkit extra = toolkit(new ControlledCodingTools());
        extra.registerTool(new RawShellTool());
        AgentScopeCodingRuntime runtime = runtime(
                new ScriptedModel(structuredResponse(validResult())),
                repeatedModel("unused", 2),
                50,
                1_024,
                64);

        assertThrows(
                IllegalArgumentException.class,
                () -> runtime.execute(new CodingSpecialistRequest(
                                specialistSession(), missing, "Do the bounded task."))
                        .block(Duration.ofSeconds(2)));
        assertThrows(
                IllegalArgumentException.class,
                () -> runtime.execute(new CodingSpecialistRequest(
                                specialistSession(), extra, "Do the bounded task."))
                        .block(Duration.ofSeconds(2)));
    }

    @Test
    void recoversPlainTextCompletionByForcingAgentScopeStructuredOutputTool() {
        ScriptedModel primary = new ScriptedModel(
                toolResponse(
                        "skill",
                        CodingSpecialistToolSurface.SKILL_LOAD_TOOL,
                        Map.of(
                                "skillId", CodingSpecialistSkillBundle.SKILL_ID,
                                "path", "SKILL.md")),
                toolResponse("plan-enter", "plan_enter", Map.of()),
                toolResponse(
                        "plan-write",
                        "plan_write",
                        Map.of("content", "1. Complete the bounded change\n2. Verify the result")),
                toolResponse(
                        "plan-exit",
                        "plan_exit",
                        Map.of("summary", "Execute the verified plan")),
                textResponse("The work is complete."),
                structuredResponse(validResult()));
        AgentScopeCodingRuntime runtime = runtime(
                primary, repeatedModel("unused compaction", 4), 40, 1_024, 64);

        CodingSpecialistRunResult result = runtime.execute(new CodingSpecialistRequest(
                        specialistSession(),
                        toolkit(new ControlledCodingTools()),
                        "Load the skill, create a recovery Plan and return the result."))
                .block(Duration.ofSeconds(10));

        assertEquals(List.of("Updated both bounded fixture files"), result.output().changeSummary());
        assertEquals(6, primary.callCount());
        assertEquals(
                new ToolChoice.Specific("generate_response"),
                primary.options(5).getToolChoice());
        assertEquals(Boolean.FALSE, primary.options(5).getParallelToolCalls());
        assertEquals(
                List.of("generate_response"),
                primary.tools(5).stream().map(tool -> tool.getName()).toList());
        assertEquals(6, result.telemetry().modelCalls());
    }

    @Test
    void entersInitialPlanModeBeforeCodingAndPersistsTheSafePoint() {
        ScriptedModel primary = new ScriptedModel(
                toolResponse(
                        "skill",
                        CodingSpecialistToolSurface.SKILL_LOAD_TOOL,
                        Map.of(
                                "skillId", CodingSpecialistSkillBundle.SKILL_ID,
                                "path", "SKILL.md")),
                toolResponse(
                        "plan-write",
                        "plan_write",
                        Map.of("content", "1. Complete the bounded task\n2. Verify the result")),
                toolResponse(
                        "plan-exit",
                        "plan_exit",
                        Map.of("summary", "Complete the planned task")),
                structuredResponse(validResult()));
        AgentScopeCodingRuntime runtime = runtime(
                primary, repeatedModel("unused compaction", 4), 40, 1_024, 64);

        CodingSpecialistRunResult result = runtime.execute(new CodingSpecialistRequest(
                        specialistSession(),
                        toolkit(new ControlledCodingTools()),
                        "Load the skill, persist the Plan and return the result."))
                .block(Duration.ofSeconds(10));

        assertEquals(List.of("Updated both bounded fixture files"), result.output().changeSummary());
        assertEquals(4, primary.callCount());
        assertEquals(4, result.telemetry().modelCalls());
        assertTrue(primary.request(0).stream()
                .flatMap(message -> message.getContent().stream())
                .anyMatch(block -> block instanceof TextBlock text
                        && text.getText().contains("PLAN MODE is active")));
    }

    @Test
    void codingPlanWorkspaceIsIsolatedByDurableAgentScopeSession() {
        AgentProfileId profileId = profileId();
        ScriptedModel model = repeatedModel("unused", 2);
        CodingSpecialistConfigurationSource configurations = (requested, version) ->
                new CodingSpecialistConfiguration(
                        requested,
                        version,
                        "primary",
                        Optional.empty(),
                        "primary",
                        "Use the fixed Coding workflow.",
                        10,
                        1,
                        0.0,
                        1.0,
                        8_192,
                        8,
                        2,
                        1_024,
                        64);
        CodingSpecialistFactory factory = new CodingSpecialistFactory(
                configurations,
                ignored -> model,
                new InMemoryAgentStateStore(),
                new CodingSpecialistSkillBundle(),
                runtimeRoot);
        String userId = "crewscope:v1:user:m4-i11-shared-principal";
        String firstSessionId = "crewscope:v1:session:m4-i11-first-task";
        String secondSessionId = "crewscope:v1:session:m4-i11-second-task";
        TaskAgentRuntimeSession firstSession = specialistSession(userId, firstSessionId);
        TaskAgentRuntimeSession secondSession = specialistSession(userId, secondSessionId);

        try (HarnessAgent first = factory.create(firstSession, toolkit(new ControlledCodingTools()));
                HarnessAgent second = factory.create(
                        secondSession, toolkit(new ControlledCodingTools()))) {
            RuntimeContext firstContext = RuntimeContext.builder()
                    .userId(userId)
                    .sessionId(firstSessionId)
                    .build();
            RuntimeContext secondContext = RuntimeContext.builder()
                    .userId(userId)
                    .sessionId(secondSessionId)
                    .build();
            assertTrue(first.workspaceFor(userId, firstSessionId)
                    .getFilesystem()
                    .write(firstContext, "plan.md", "first task plan")
                    .isSuccess());

            assertFalse(second.workspaceFor(userId, secondSessionId)
                    .getFilesystem()
                    .exists(secondContext, "plan.md"));
        }
    }

    @Test
    void m5ResolvedFactoryKeepsTheM4CodingHarnessAndUsesThePreflightedModelAndPrompt() {
        ScriptedModel deploymentModel = repeatedModel("unused deployment model", 2);
        ScriptedModel resolvedModel = repeatedModel("unused resolved model", 2);
        AgentProfileId profileId = profileId();
        CodingSpecialistConfigurationSource configurations = (requested, version) ->
                new CodingSpecialistConfiguration(
                        requested,
                        version,
                        "deployment",
                        Optional.empty(),
                        "deployment",
                        "Deployment prompt must be replaced.",
                        20,
                        3,
                        0.8,
                        0.8,
                        4_096,
                        8,
                        2,
                        1_024,
                        64);
        CodingSpecialistFactory factory = new CodingSpecialistFactory(
                configurations,
                ignored -> deploymentModel,
                new InMemoryAgentStateStore(),
                new CodingSpecialistSkillBundle(),
                runtimeRoot);
        SafeModelGenerateOptions options = new SafeModelGenerateOptions(
                Optional.of(BigDecimal.ZERO),
                Optional.of(BigDecimal.ONE),
                Optional.of(8_192L),
                AgentReasoningMode.DEFAULT,
                false,
                false,
                Optional.empty(),
                2);

        try (HarnessAgent agent = factory.createResolved(
                specialistSession(),
                toolkit(new ControlledCodingTools()),
                resolvedModel,
                Optional.empty(),
                "M5 exact Template prompt.",
                options)) {
            assertSame(resolvedModel, agent.getModel());
            assertEquals("M5 exact Template prompt.", agent.getDelegate().getSysPrompt());
            assertEquals(20, agent.getMaxIters());
            assertEquals(8_192, agent.getDelegate().getGenerateOptions().getMaxTokens());
            assertNotNull(agent.getCompactionHook());
            Set<String> initialRuntimeTools = new java.util.HashSet<>(
                    CodingSpecialistToolSurface.runtimeTools());
            // AgentScope installs the fixed read-only Skill loader at invocation time.
            initialRuntimeTools.remove(CodingSpecialistToolSurface.SKILL_LOAD_TOOL);
            assertEquals(
                    initialRuntimeTools,
                    agent.getToolkit().getToolNames());
        }
    }

    private AgentScopeCodingRuntime runtime(
            ScriptedModel primary,
            ScriptedModel compaction,
            int compactionMessages,
            int evictionChars,
            int previewChars) {
        AgentProfileId profileId = profileId();
        CodingSpecialistConfigurationSource configurations = (requested, version) ->
                new CodingSpecialistConfiguration(
                        requested,
                        version,
                        "primary",
                        Optional.empty(),
                        "compaction",
                        "You are CrewScope's Coding Specialist. Load the fixed skill first, use Plan and Todo, then analyze, change, test, inspect the diff and return the required structured output.",
                        30,
                        2,
                        0.0,
                        1.0,
                        8_192,
                        compactionMessages,
                        2,
                        evictionChars,
                        previewChars);
        CodingSpecialistFactory factory = new CodingSpecialistFactory(
                configurations,
                modelId -> "compaction".equals(modelId) ? compaction : primary,
                new InMemoryAgentStateStore(),
                new CodingSpecialistSkillBundle(),
                runtimeRoot);
        return new AgentScopeCodingRuntime(factory);
    }

    private TaskAgentRuntimeSession specialistSession() {
        return specialistSession(
                "crewscope:v1:user:m4-i11", "crewscope:v1:session:m4-i11");
    }

    private TaskAgentRuntimeSession specialistSession(String userId, String sessionId) {
        TaskAgentRuntimeSession session = mock(TaskAgentRuntimeSession.class);
        when(session.purpose()).thenReturn(TaskAgentSessionPurpose.SPECIALIST);
        when(session.canInvoke()).thenReturn(true);
        when(session.agentProfileId()).thenReturn(profileId());
        when(session.agentProfileVersion()).thenReturn(1L);
        when(session.agentScopeKey()).thenReturn(new AgentScopeSessionKey(userId, sessionId));
        return session;
    }

    private static AgentProfileId profileId() {
        return AgentProfileId.from("11111111-1111-4111-8111-111111111111");
    }

    private static Toolkit toolkit(Object tools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        return toolkit;
    }

    private static List<Map<String, Object>> todos(String status) {
        return List.of(
                Map.of("content", "Analyze and plan", "status", status, "priority", "high"),
                Map.of("content", "Modify both files", "status", status, "priority", "high"),
                Map.of("content", "Test and self-check", "status", status, "priority", "high"));
    }

    private static ChatResponse toolResponse(
            String callId, String toolName, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(List.of(ToolUseBlock.builder()
                        .id(callId)
                        .name(toolName)
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build()))
                .usage(new ChatUsage(12, 8, 0.01))
                .build();
    }

    private static ChatResponse structuredResponse(Map<String, Object> response) {
        return toolResponse("structured", "generate_response", Map.of("response", response));
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(io.agentscope.core.message.TextBlock.builder()
                        .text(text)
                        .build()))
                .usage(new ChatUsage(12, 8, 0.01))
                .finishReason("stop")
                .build();
    }

    private static ScriptedModel repeatedModel(String response, int count) {
        String[] responses = new String[count];
        Arrays.fill(responses, response);
        return new ScriptedModel(responses);
    }

    private static Map<String, Object> validResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "1");
        result.put("changeSummary", List.of("Updated both bounded fixture files"));
        result.put("limitations", List.of());
        result.put("risks", List.of());
        return result;
    }

    @SuppressWarnings("unused")
    private static final class ControlledCodingTools {

        private final Map<String, String> files = new LinkedHashMap<>(Map.of(
                "src/main/java/example/One.java", "before",
                "src/main/java/example/Two.java", "before"));
        private boolean tested;
        private boolean diffInspected;

        @Tool(name = "repository_tree", description = "tree", readOnly = true)
        public String tree() { return String.join("\n", files.keySet()); }

        @Tool(name = "repository_list", description = "list", readOnly = true)
        public String list() { return String.join("\n", files.keySet()); }

        @Tool(name = "repository_read", description = "read", readOnly = true)
        public String read(@ToolParam(name = "path") String path) { return files.get(path); }

        @Tool(name = "repository_grep", description = "grep", readOnly = true)
        public String grep() { return "before"; }

        @Tool(name = "repository_glob", description = "glob", readOnly = true)
        public String glob() { return String.join("\n", files.keySet()); }

        @Tool(name = "repository_git_history", description = "history", readOnly = true)
        public String history() { return "baseline"; }

        @Tool(name = "repository_git_status", description = "status", readOnly = true)
        public String status() { return files.toString(); }

        @Tool(name = "repository_git_diff", description = "diff", readOnly = true)
        public String diff() {
            diffInspected = true;
            return files.toString();
        }

        @Tool(name = "coding_create", description = "create")
        public String create() { return "created"; }

        @Tool(name = "coding_edit", description = "edit")
        public String edit(
                @ToolParam(name = "path") String path,
                @ToolParam(name = "old_text") String oldText,
                @ToolParam(name = "new_text") String newText,
                @ToolParam(name = "replace_all") boolean replaceAll) {
            files.compute(path, (ignored, value) -> value.replace(oldText, newText));
            return "edited";
        }

        @Tool(name = "coding_patch", description = "patch")
        public String patch() { return "patched"; }

        @Tool(name = "coding_move", description = "move")
        public String move() { return "moved"; }

        @Tool(name = "coding_delete", description = "delete")
        public String delete() { return "deleted"; }

        @Tool(name = "coding_run_command", description = "run")
        public String run(@ToolParam(name = "command_kind") String commandKind) {
            tested = "TEST".equals(commandKind)
                    && files.values().stream().allMatch("after"::equals);
            return "TEST_OK\n" + "x".repeat(1_000);
        }
    }

    @SuppressWarnings("unused")
    private static final class RawShellTool {
        @Tool(name = "execute", description = "forbidden raw shell")
        public String execute() { return "forbidden"; }
    }
}
