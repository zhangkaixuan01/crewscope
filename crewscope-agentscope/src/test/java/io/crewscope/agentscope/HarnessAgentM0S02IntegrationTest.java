package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.ToolMergeMode;
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
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/** M0-S02 integration evidence for structured output, AG-UI and HITL on AgentScope 2.0.0. */
@Tag("integration")
class HarnessAgentM0S02IntegrationTest {

    private static final String USER_ID = "member-zhang";
    private static final String SESSION_ID = "conversation-crw-m0-s02";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir Path workspace;

    @Test
    void structuredOutputIsMappedAndPassesCrewScopeBeanValidation() {
        ScriptedModel model =
                new ScriptedModel(
                        structuredResponse(
                                "Implement M0-S02",
                                List.of("Structured output is validated", "AG-UI streams"),
                                2));

        Msg result;
        try (HarnessAgent agent = newAgent(model, new Toolkit(), "structured-valid")) {
            result =
                    agent.call(
                                    List.of(new UserMessage("Create the M0-S02 task intent")),
                                    TaskIntentProbe.class,
                                    sessionContext("structured-valid"))
                            .block(TIMEOUT);
        }

        assertNotNull(result);
        TaskIntentProbe intent = result.getStructuredData(TaskIntentProbe.class);
        Set<ConstraintViolation<TaskIntentProbe>> violations = validator().validate(intent);

        assertEquals("Implement M0-S02", intent.objective());
        assertEquals(2, intent.acceptanceCriteria().size());
        assertEquals(2, intent.riskLevel());
        assertTrue(violations.isEmpty());
        assertEquals(1, model.callCount());
    }

    @Test
    void invalidStructuredOutputIsRejectedByCrewScopeBeanValidation() {
        ScriptedModel model =
                new ScriptedModel(structuredResponse(" ", List.of(), 0));

        TaskIntentProbe intent;
        try (HarnessAgent agent = newAgent(model, new Toolkit(), "structured-invalid")) {
            Msg result =
                    agent.call(
                                    List.of(new UserMessage("Create an invalid task intent")),
                                    TaskIntentProbe.class,
                                    sessionContext("structured-invalid"))
                            .block(TIMEOUT);
            assertNotNull(result);
            intent = result.getStructuredData(TaskIntentProbe.class);
        }

        Set<String> invalidFields =
                validator().validate(intent).stream()
                        .map(violation -> violation.getPropertyPath().toString())
                        .collect(java.util.stream.Collectors.toSet());

        // AgentScope maps schema-compatible data; CrewScope owns application validation.
        assertEquals(Set.of("objective", "acceptanceCriteria", "riskLevel"), invalidFields);
    }

    @Test
    void officialAguiAdapterEmitsStableTextStreamingSequence() {
        ScriptedModel model = new ScriptedModel("AG-UI baseline complete");
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("conversation-crw-agui")
                        .runId("run-m0-s02-agui")
                        .messages(List.of(AguiMessage.userMessage("message-1", "Run AG-UI probe")))
                        .build();

        List<AguiEvent> events;
        try (HarnessAgent agent = newAgent(model, new Toolkit(), "agui")) {
            AguiAgentAdapter adapter =
                    new AguiAgentAdapter(
                            agent,
                            AguiAdapterConfig.builder()
                                    .toolMergeMode(ToolMergeMode.AGENT_ONLY)
                                    .build());
            events = adapter.run(input).collectList().block(TIMEOUT);
        }

        assertNotNull(events);
        assertEquals(
                List.of(
                        AguiEventType.RUN_STARTED,
                        AguiEventType.TEXT_MESSAGE_START,
                        AguiEventType.TEXT_MESSAGE_CONTENT,
                        AguiEventType.TEXT_MESSAGE_END,
                        AguiEventType.RUN_FINISHED),
                events.stream().map(AguiEvent::getType).toList());
        assertTrue(
                events.stream()
                        .allMatch(
                                event ->
                                        input.getThreadId().equals(event.getThreadId())
                                                && input.getRunId().equals(event.getRunId())));
    }

    @Test
    void askingToolEmitsInterruptAndConfirmedResumeExecutesOnce() {
        CountingAskingTool tool = new CountingAskingTool();
        Toolkit toolkit = toolkitWith(tool);
        ScriptedModel model =
                new ScriptedModel(
                        toolUseResponse("tool-call-1", tool.getName(), "publish preview"),
                        textResponse("confirmed-action-complete"));

        try (HarnessAgent agent = newAgent(model, toolkit, "hitl-resume")) {
            List<AgentEvent> interrupted =
                    agent.streamEvents(
                                    List.of(new UserMessage("Prepare the reviewed action")),
                                    sessionContext("hitl-resume"))
                            .collectList()
                            .block(TIMEOUT);

            assertNotNull(interrupted);
            int confirmIndex = indexOf(interrupted, RequireUserConfirmEvent.class);
            int stopIndex = indexOf(interrupted, RequestStopEvent.class);
            Msg pendingResult = resultOf(interrupted);
            ToolUseBlock pending = onlyPendingToolCall(pendingResult);

            assertTrue(confirmIndex >= 0);
            assertTrue(stopIndex > confirmIndex);
            assertEquals(GenerateReason.PERMISSION_ASKING, pendingResult.getGenerateReason());
            assertEquals(0, tool.executionCount());

            Msg resumed =
                    agent.call(
                                    List.of(confirmMessage(pending)),
                                    sessionContext("hitl-resume"))
                            .block(TIMEOUT);

            assertNotNull(resumed);
            assertEquals("confirmed-action-complete", resumed.getTextContent());
            assertEquals(1, tool.executionCount());
            assertEquals(2, model.callCount());
        }
    }

    @Test
    void nativeDuplicateResumeReentersModelAndRequiresCrewScopeIdempotency() {
        CountingAskingTool tool = new CountingAskingTool();
        ScriptedModel model =
                new ScriptedModel(
                        toolUseResponse("tool-call-native", tool.getName(), "native duplicate"),
                        textResponse("first-resume-result"),
                        textResponse("duplicate-resume-result"));

        try (HarnessAgent agent = newAgent(model, toolkitWith(tool), "native-duplicate")) {
            Msg interrupted =
                    agent.call("Prepare native duplicate probe", sessionContext("native-duplicate"))
                            .block(TIMEOUT);
            ToolUseBlock pending = onlyPendingToolCall(interrupted);
            Msg confirmation = confirmMessage(pending);

            Msg first =
                    agent.call(List.of(confirmation), sessionContext("native-duplicate"))
                            .block(TIMEOUT);
            Msg duplicate =
                    agent.call(List.of(confirmation), sessionContext("native-duplicate"))
                            .block(TIMEOUT);

            assertNotNull(first);
            assertNotNull(duplicate);
            assertEquals("first-resume-result", first.getTextContent());
            assertEquals("duplicate-resume-result", duplicate.getTextContent());
            assertEquals(1, tool.executionCount());
            assertEquals(3, model.callCount());
        }
    }

    @Test
    void crewScopeResumeRequestGuardReturnsCachedResultForDuplicateRequest() {
        CountingAskingTool tool = new CountingAskingTool();
        ScriptedModel model =
                new ScriptedModel(
                        toolUseResponse("tool-call-guarded", tool.getName(), "guarded duplicate"),
                        textResponse("guarded-resume-result"));
        ResumeRequestGuardProbe guard = new ResumeRequestGuardProbe();

        try (HarnessAgent agent = newAgent(model, toolkitWith(tool), "guarded-duplicate")) {
            Msg interrupted =
                    agent.call("Prepare guarded duplicate probe", sessionContext("guarded-duplicate"))
                            .block(TIMEOUT);
            Msg confirmation = confirmMessage(onlyPendingToolCall(interrupted));
            Supplier<Msg> resume =
                    () ->
                            agent.call(
                                            List.of(confirmation),
                                            sessionContext("guarded-duplicate"))
                                    .block(TIMEOUT);

            Msg first = guard.resume("resume-request-1", resume);
            Msg duplicate = guard.resume("resume-request-1", resume);

            assertNotNull(first);
            assertSame(first, duplicate);
            assertEquals("guarded-resume-result", duplicate.getTextContent());
            assertEquals(1, tool.executionCount());
            assertEquals(2, model.callCount());
        }
    }

    private HarnessAgent newAgent(ScriptedModel model, Toolkit toolkit, String sessionSuffix) {
        return HarnessAgent.builder()
                .name("crewscope-m0-s02-" + sessionSuffix)
                .sysPrompt("You are the deterministic CrewScope M0-S02 runtime probe.")
                .model(model)
                .toolkit(toolkit)
                .workspace(workspace)
                .stateStore(new InMemoryAgentStateStore())
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
                .enableAgentTracingLog(false)
                .build();
    }

    private static RuntimeContext sessionContext(String suffix) {
        return RuntimeContext.builder()
                .userId(USER_ID)
                .sessionId(SESSION_ID + "-" + suffix)
                .build();
    }

    private static Validator validator() {
        return Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static Toolkit toolkitWith(ToolBase tool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        return toolkit;
    }

    private static Msg confirmMessage(ToolUseBlock pending) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Msg.METADATA_CONFIRM_RESULTS, List.of(new ConfirmResult(true, pending)));
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("[confirm]")
                .metadata(metadata)
                .build();
    }

    private static ToolUseBlock onlyPendingToolCall(Msg result) {
        assertNotNull(result);
        List<ToolUseBlock> calls = result.getContentBlocks(ToolUseBlock.class);
        assertEquals(1, calls.size());
        assertEquals(ToolCallState.ASKING, calls.get(0).getState());
        return calls.get(0);
    }

    private static int indexOf(List<AgentEvent> events, Class<?> eventType) {
        for (int index = 0; index < events.size(); index++) {
            if (eventType.isInstance(events.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static Msg resultOf(List<AgentEvent> events) {
        return events.stream()
                .filter(AgentResultEvent.class::isInstance)
                .map(AgentResultEvent.class::cast)
                .map(AgentResultEvent::getResult)
                .findFirst()
                .orElseThrow(() -> new AssertionError("AGENT_RESULT event was not emitted"));
    }

    private static ChatResponse structuredResponse(
            String objective, List<String> acceptanceCriteria, int riskLevel) {
        Map<String, Object> input =
                Map.of(
                        "response",
                        Map.of(
                                "objective", objective,
                                "acceptanceCriteria", acceptanceCriteria,
                                "riskLevel", riskLevel));
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id("generate-response-call")
                                        .name("generate_response")
                                        .input(input)
                                        .content(JsonUtils.getJsonCodec().toJson(input))
                                        .build()))
                .usage(new ChatUsage(12, 8, 0.01))
                .build();
    }

    private static ChatResponse toolUseResponse(
            String toolCallId, String toolName, String query) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(toolCallId)
                                        .name(toolName)
                                        .input(Map.of("query", query))
                                        .build()))
                .usage(new ChatUsage(10, 6, 0.01))
                .build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(8, 4, 0.01))
                .finishReason("stop")
                .build();
    }

    /** Minimal versioned shape used to prove the Structured Output-to-command boundary. */
    record TaskIntentProbe(
            @NotBlank String objective,
            @Size(min = 1) List<@NotBlank String> acceptanceCriteria,
            @Min(1) @Max(3) int riskLevel) {}

    /** Side-effecting tool that always requires explicit user confirmation. */
    private static final class CountingAskingTool extends ToolBase {

        private final AtomicInteger executionCount = new AtomicInteger();

        private CountingAskingTool() {
            super(
                    ToolBase.builder()
                            .name("publish_reviewed_action")
                            .description("Publishes a reviewed CrewScope action")
                            .inputSchema(
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of("query", Map.of("type", "string"))))
                            .readOnly(false)
                            .concurrencySafe(false));
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("Action requires owner confirmation"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executionCount.incrementAndGet();
            Object query = param.getInput() == null ? "" : param.getInput().get("query");
            return Mono.just(ToolResultBlock.text("executed:" + query));
        }

        private int executionCount() {
            return executionCount.get();
        }
    }

    /**
     * Test-only boundary probe. Production persistence and concurrency semantics belong to the
     * Confirmation application service; this establishes that deduplication precedes AgentScope.
     */
    private static final class ResumeRequestGuardProbe {

        private final Map<String, Msg> completedResults = new ConcurrentHashMap<>();

        private Msg resume(String requestId, Supplier<Msg> action) {
            return completedResults.computeIfAbsent(requestId, ignored -> action.get());
        }
    }
}
