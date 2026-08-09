package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import io.crewscope.application.conversation.ClarificationRequestV1;
import io.crewscope.application.conversation.TaskIntentV1;
import io.crewscope.domain.conversation.TaskIntentProposal;
import io.crewscope.domain.conversation.TaskIntentResponsibility;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/** M2-S03 evidence for versioned structured intent and clarification recovery boundaries. */
@Tag("integration")
class HarnessAgentM2S03StructuredConversationIntegrationTest {

    private static final String USER_ID = "member-m2-s03";
    private static final String SESSION_ID = "conversation-m2-s03";
    private static final String PROJECT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String OWNER_ID = "22222222-2222-4222-8222-222222222222";
    private static final String EXECUTOR_ID = "33333333-3333-4333-8333-333333333333";
    private static final String REVIEWER_ID = "44444444-4444-4444-8444-444444444444";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @TempDir Path workspace;

    @Test
    void mapsValidTaskIntentWithOfficialM2SchemaAndBeanValidation() {
        ScriptedModel model = new ScriptedModel(structuredResponse("task-valid", validTaskIntent()));

        TaskIntentV1 intent;
        try (HarnessAgent agent = newAgent(model, new Toolkit(), "task-valid")) {
            Msg result = agent.call(
                            List.of(new UserMessage("Create a task proposal")),
                            TaskIntentV1.class,
                            sessionContext("task-valid"))
                    .block(TIMEOUT);

            assertNotNull(result);
            intent = result.getStructuredData(TaskIntentV1.class);
        }

        assertEquals("1", intent.schemaVersion());
        assertEquals(PROJECT_ID, intent.workProjectId());
        assertEquals(OWNER_ID, intent.ownerMemberId());
        assertTrue(VALIDATOR.validate(intent).isEmpty());
        assertEquals(1, model.callCount());
    }

    @Test
    void mapsNestedClarificationRequestWithOfficialM2SchemaAndBeanValidation() {
        ScriptedModel model =
                new ScriptedModel(structuredResponse("clarification-valid", validClarification()));

        ClarificationRequestV1 clarification;
        try (HarnessAgent agent = newAgent(model, new Toolkit(), "clarification-valid")) {
            Msg result = agent.call(
                            List.of(new UserMessage("Clarify the repository")),
                            ClarificationRequestV1.class,
                            sessionContext("clarification-valid"))
                    .block(TIMEOUT);

            assertNotNull(result);
            clarification = result.getStructuredData(ClarificationRequestV1.class);
        }

        assertTrue(VALIDATOR.validate(clarification).isEmpty());
        assertEquals("repository", clarification.questions().get(0).fieldKey());
        assertEquals(List.of("crewscope-java", "agentscope-java"),
                clarification.questions().get(0).choices());
        assertEquals(1, model.callCount());
    }

    @Test
    void schemaErrorProducesToolErrorAndAllowsModelToRepairOutput() {
        ScriptedModel model = new ScriptedModel(
                invalidStructuredResponse("task-invalid-schema"),
                structuredResponse("task-repaired", validTaskIntent()));

        TaskIntentV1 intent;
        try (HarnessAgent agent = newAgent(model, new Toolkit(), "schema-repair")) {
            Msg result = agent.call(
                            List.of(new UserMessage("Create a valid task proposal")),
                            TaskIntentV1.class,
                            sessionContext("schema-repair"))
                    .block(TIMEOUT);

            assertNotNull(result);
            intent = result.getStructuredData(TaskIntentV1.class);
        }

        assertTrue(VALIDATOR.validate(intent).isEmpty());
        assertEquals(2, model.callCount());
        assertTrue(containsToolResultText(
                model.request(1), "Parameter validation failed"));
    }

    @Test
    void beanValidationRejectsSchemaCompatibleTaskIntent() {
        Map<String, Object> invalid = validTaskIntent();
        invalid.put("objective", " ");
        invalid.put("acceptanceCriteria", List.of());
        ScriptedModel model =
                new ScriptedModel(structuredResponse("task-bean-invalid", invalid));

        TaskIntentV1 intent;
        try (HarnessAgent agent = newAgent(model, new Toolkit(), "bean-invalid")) {
            Msg result = agent.call(
                            List.of(new UserMessage("Create a schema-compatible invalid intent")),
                            TaskIntentV1.class,
                            sessionContext("bean-invalid"))
                    .block(TIMEOUT);
            assertNotNull(result);
            intent = result.getStructuredData(TaskIntentV1.class);
        }

        Set<String> invalidFields = VALIDATOR.validate(intent).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("objective", "acceptanceCriteria"), invalidFields);
        assertEquals(1, model.callCount());
    }

    @Test
    void domainRejectsBusinessConflictThatSchemaAndBeanValidationAccept() {
        Map<String, Object> conflicting = validTaskIntent();
        conflicting.put("acceptanceCriteria", List.of("No duplicate", "No duplicate"));
        ScriptedModel model =
                new ScriptedModel(structuredResponse("task-business-conflict", conflicting));

        TaskIntentV1 intent;
        try (HarnessAgent agent = newAgent(model, new Toolkit(), "business-conflict")) {
            Msg result = agent.call(
                            List.of(new UserMessage("Create a conflicting task proposal")),
                            TaskIntentV1.class,
                            sessionContext("business-conflict"))
                    .block(TIMEOUT);
            assertNotNull(result);
            intent = result.getStructuredData(TaskIntentV1.class);
        }

        assertTrue(VALIDATOR.validate(intent).isEmpty());
        TaskIntentResponsibility owner = new TaskIntentResponsibility(
                ResponsibilityRole.OWNER,
                PrincipalId.from(OWNER_ID),
                PrincipalType.USER,
                Optional.of(TeamMemberId.generate()));
        WorkItemScope scope = new WorkItemScope(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.from(PROJECT_ID));

        assertThrows(
                DomainValidationException.class,
                () -> new TaskIntentProposal(
                        scope,
                        intent.objective(),
                        intent.acceptanceCriteria(),
                        owner,
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void clarificationInterruptResumesWithBoundAnswerAndDeduplicatesRequest() {
        ClarificationTool tool = new ClarificationTool();
        ScriptedModel model = new ScriptedModel(
                clarificationToolResponse("clarification-call", tool.getName()),
                structuredResponse("task-after-answer", validTaskIntent()));
        ResumeGuardProbe guard = new ResumeGuardProbe();
        RuntimeContext context = sessionContext("clarification-resume");

        try (HarnessAgent agent =
                newAgent(model, toolkitWith(tool), "clarification-resume")) {
            List<AgentEvent> interrupted = agent.streamEvents(
                            List.of(new UserMessage("Prepare a repository task")), context)
                    .collectList()
                    .block(TIMEOUT);

            assertNotNull(interrupted);
            int confirmIndex = indexOf(interrupted, RequireUserConfirmEvent.class);
            int stopIndex = indexOf(interrupted, RequestStopEvent.class);
            RequireUserConfirmEvent confirmation = interrupted.stream()
                    .filter(RequireUserConfirmEvent.class::isInstance)
                    .map(RequireUserConfirmEvent.class::cast)
                    .findFirst()
                    .orElseThrow();
            Msg pendingResult = resultOf(interrupted);
            ToolUseBlock pending = onlyPendingToolCall(pendingResult);

            assertTrue(confirmIndex >= 0);
            assertTrue(stopIndex > confirmIndex);
            assertEquals(GenerateReason.PERMISSION_ASKING, pendingResult.getGenerateReason());
            assertEquals(0, tool.executionCount());

            PendingResume pendingResume = new PendingResume(
                    "invocation-1",
                    SESSION_ID + "-clarification-resume",
                    confirmation.getReplyId(),
                    pending.getId(),
                    TaskIntentV1.SCHEMA_VERSION,
                    Instant.parse("2026-08-09T12:10:00Z"));
            ResumeRequest request = new ResumeRequest(
                    "resume-key-1",
                    "invocation-1",
                    SESSION_ID + "-clarification-resume",
                    confirmation.getReplyId(),
                    pending.getId(),
                    TaskIntentV1.SCHEMA_VERSION,
                    Map.of("repository", "crewscope-java"),
                    Instant.parse("2026-08-09T12:00:00Z"));
            Supplier<Msg> resume = () -> agent.call(
                            List.of(answerMessage(pending, request.answers())),
                            TaskIntentV1.class,
                            context)
                    .block(TIMEOUT);

            Msg first = guard.resume(pendingResume, request, resume);
            Msg duplicate = guard.resume(pendingResume, request, resume);
            TaskIntentV1 intent = first.getStructuredData(TaskIntentV1.class);
            ResumeRequest conflictingDuplicate = new ResumeRequest(
                    request.requestId(),
                    request.invocationId(),
                    request.sessionId(),
                    request.replyId(),
                    request.toolCallId(),
                    request.schemaVersion(),
                    Map.of("repository", "agentscope-java"),
                    request.receivedAt());

            assertSame(first, duplicate);
            assertThrows(
                    ResumeRejectedException.class,
                    () -> guard.resume(pendingResume, conflictingDuplicate, resume));
            assertTrue(VALIDATOR.validate(intent).isEmpty());
            assertEquals(Map.of("repository", "crewscope-java"), tool.lastAnswers());
            assertEquals(1, tool.executionCount());
            assertEquals(2, model.callCount());
            assertTrue(containsToolResultText(model.request(1), "crewscope-java"));
        }
    }

    @Test
    void expiredAndMismatchedResumeAreRejectedBeforeAgentScope() {
        ClarificationTool tool = new ClarificationTool();
        ScriptedModel model = new ScriptedModel(
                clarificationToolResponse("expiring-call", tool.getName()),
                structuredResponse("must-not-run", validTaskIntent()));
        ResumeGuardProbe guard = new ResumeGuardProbe();
        RuntimeContext context = sessionContext("expired-resume");

        try (HarnessAgent agent = newAgent(model, toolkitWith(tool), "expired-resume")) {
            Msg interrupted = agent.call("Prepare expiring clarification", context).block(TIMEOUT);
            ToolUseBlock pending = onlyPendingToolCall(interrupted);
            PendingResume expected = new PendingResume(
                    "invocation-expiring",
                    SESSION_ID + "-expired-resume",
                    "reply-expiring",
                    pending.getId(),
                    TaskIntentV1.SCHEMA_VERSION,
                    Instant.parse("2026-08-09T12:05:00Z"));
            Supplier<Msg> forbiddenResume = () -> agent.call(
                            List.of(answerMessage(
                                    pending, Map.of("repository", "crewscope-java"))),
                            TaskIntentV1.class,
                            context)
                    .block(TIMEOUT);

            ResumeRequest mismatched = new ResumeRequest(
                    "resume-mismatch",
                    expected.invocationId(),
                    expected.sessionId(),
                    expected.replyId(),
                    "wrong-tool-call",
                    expected.schemaVersion(),
                    Map.of("repository", "crewscope-java"),
                    Instant.parse("2026-08-09T12:00:00Z"));
            ResumeRequest expired = new ResumeRequest(
                    "resume-expired",
                    expected.invocationId(),
                    expected.sessionId(),
                    expected.replyId(),
                    expected.toolCallId(),
                    expected.schemaVersion(),
                    Map.of("repository", "crewscope-java"),
                    Instant.parse("2026-08-09T12:05:00Z"));

            assertThrows(
                    ResumeRejectedException.class,
                    () -> guard.resume(expected, mismatched, forbiddenResume));
            assertThrows(
                    ResumeRejectedException.class,
                    () -> guard.resume(expected, expired, forbiddenResume));
            assertEquals(0, tool.executionCount());
            assertEquals(1, model.callCount());
        }
    }

    private HarnessAgent newAgent(ScriptedModel model, Toolkit toolkit, String sessionSuffix) {
        return HarnessAgent.builder()
                .name("crewscope-m2-s03-" + sessionSuffix)
                .sysPrompt("You are the deterministic CrewScope M2-S03 runtime probe.")
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

    private static Toolkit toolkitWith(ToolBase tool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        return toolkit;
    }

    private static Map<String, Object> validTaskIntent() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemaVersion", "1");
        response.put("objective", "Implement repository conversation workflow");
        response.put(
                "acceptanceCriteria",
                List.of("Repository is explicit", "Responsibility is auditable"));
        response.put("workProjectId", PROJECT_ID);
        response.put("ownerMemberId", OWNER_ID);
        response.put("executorPrincipalId", EXECUTOR_ID);
        response.put("gateReviewerMemberId", REVIEWER_ID);
        return response;
    }

    private static Map<String, Object> validClarification() {
        return Map.of(
                "schemaVersion",
                "1",
                "summary",
                "The target repository is required",
                "questions",
                List.of(Map.of(
                        "fieldKey",
                        "repository",
                        "question",
                        "Which repository should be changed?",
                        "context",
                        "The conversation mentions two repositories.",
                        "required",
                        true,
                        "choices",
                        List.of("crewscope-java", "agentscope-java"))));
    }

    private static ChatResponse structuredResponse(
            String toolCallId, Map<String, Object> response) {
        Map<String, Object> input = Map.of("response", response);
        return toolResponse(toolCallId, "generate_response", input);
    }

    private static ChatResponse invalidStructuredResponse(String toolCallId) {
        return toolResponse(toolCallId, "generate_response", Map.of("unexpected", "value"));
    }

    private static ChatResponse clarificationToolResponse(String toolCallId, String toolName) {
        return toolResponse(toolCallId, toolName, Map.of("request", validClarification()));
    }

    private static ChatResponse toolResponse(
            String toolCallId, String toolName, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(List.of(ToolUseBlock.builder()
                        .id(toolCallId)
                        .name(toolName)
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build()))
                .usage(new ChatUsage(12, 8, 0.01))
                .build();
    }

    private static Msg answerMessage(ToolUseBlock pending, Map<String, String> answers) {
        Map<String, Object> resumedInput = new LinkedHashMap<>(pending.getInput());
        resumedInput.put("answers", Map.copyOf(answers));
        ToolUseBlock answeredCall = ToolUseBlock.builder()
                .id(pending.getId())
                .name(pending.getName())
                .input(resumedInput)
                .content(JsonUtils.getJsonCodec().toJson(resumedInput))
                .metadata(pending.getMetadata())
                .state(pending.getState())
                .build();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(
                Msg.METADATA_CONFIRM_RESULTS,
                List.of(new ConfirmResult(true, answeredCall)));
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("[clarification-answer]")
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

    private static boolean containsToolResultText(List<Msg> messages, String expected) {
        return messages.stream()
                .flatMap(message ->
                        message.getContentBlocks(ToolResultBlock.class).stream())
                .flatMap(result -> result.getOutput().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .anyMatch(text -> text.contains(expected));
    }

    /** Read-only bridge tool; it returns normalized answers without external side effects. */
    private static final class ClarificationTool extends ToolBase {

        private final AtomicInteger executionCount = new AtomicInteger();
        private volatile Map<String, String> lastAnswers = Map.of();

        private ClarificationTool() {
            super(ToolBase.builder()
                    .name("request_clarification")
                    .description("Pauses until CrewScope binds validated clarification answers")
                    .inputSchema(clarificationToolSchema())
                    .readOnly(true)
                    .concurrencySafe(true));
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("Clarification answer is required"));
        }

        @Override
        @SuppressWarnings("unchecked")
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executionCount.incrementAndGet();
            Map<String, Object> rawAnswers =
                    (Map<String, Object>) param.getInput().getOrDefault("answers", Map.of());
            Map<String, String> normalized = new LinkedHashMap<>();
            rawAnswers.forEach((key, value) -> normalized.put(key, String.valueOf(value).strip()));
            lastAnswers = Map.copyOf(normalized);
            return Mono.just(ToolResultBlock.text(
                    "clarification_answers=" + JsonUtils.getJsonCodec().toJson(lastAnswers)));
        }

        private int executionCount() {
            return executionCount.get();
        }

        private Map<String, String> lastAnswers() {
            return lastAnswers;
        }
    }

    private static Map<String, Object> clarificationToolSchema() {
        Map<String, Object> question = Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "fieldKey", Map.of("type", "string"),
                        "question", Map.of("type", "string"),
                        "context", Map.of("type", "string"),
                        "required", Map.of("type", "boolean"),
                        "choices", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required",
                List.of("fieldKey", "question", "context", "required", "choices"));
        Map<String, Object> request = Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "schemaVersion", Map.of("type", "string"),
                        "summary", Map.of("type", "string"),
                        "questions", Map.of("type", "array", "items", question)),
                "required",
                List.of("schemaVersion", "summary", "questions"));
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "request", request,
                        "answers", Map.of("type", "object")),
                "required",
                List.of("request"));
    }

    private record PendingResume(
            String invocationId,
            String sessionId,
            String replyId,
            String toolCallId,
            String schemaVersion,
            Instant expiresAt) {}

    private record ResumeRequest(
            String requestId,
            String invocationId,
            String sessionId,
            String replyId,
            String toolCallId,
            String schemaVersion,
            Map<String, String> answers,
            Instant receivedAt) {}

    /** Test-only proof that scope, expiry and idempotency are decided before AgentScope. */
    private static final class ResumeGuardProbe {

        private final Map<String, CompletedResume> completed = new ConcurrentHashMap<>();

        private Msg resume(
                PendingResume pending, ResumeRequest request, Supplier<Msg> agentScopeResume) {
            String requestHash = requestHash(request);
            CompletedResume replay = completed.get(request.requestId());
            if (replay != null) {
                if (!replay.requestHash().equals(requestHash)) {
                    throw new ResumeRejectedException("idempotency conflict");
                }
                return replay.result();
            }
            requireCurrentPending(pending, request);
            if (!request.receivedAt().isBefore(pending.expiresAt())) {
                throw new ResumeRejectedException("clarification expired");
            }
            CompletedResume result = completed.computeIfAbsent(
                    request.requestId(),
                    ignored -> new CompletedResume(requestHash, agentScopeResume.get()));
            if (!result.requestHash().equals(requestHash)) {
                throw new ResumeRejectedException("idempotency conflict");
            }
            return result.result();
        }

        private static void requireCurrentPending(
                PendingResume pending, ResumeRequest request) {
            if (!pending.invocationId().equals(request.invocationId())
                    || !pending.sessionId().equals(request.sessionId())
                    || !pending.replyId().equals(request.replyId())
                    || !pending.toolCallId().equals(request.toolCallId())
                    || !pending.schemaVersion().equals(request.schemaVersion())) {
                throw new ResumeRejectedException("resume does not match current clarification");
            }
        }

        private static String requestHash(ResumeRequest request) {
            return String.join(
                    "|",
                    request.invocationId(),
                    request.sessionId(),
                    request.replyId(),
                    request.toolCallId(),
                    request.schemaVersion(),
                    new TreeMap<>(request.answers()).toString());
        }
    }

    private record CompletedResume(String requestHash, Msg result) {}

    private static final class ResumeRejectedException extends RuntimeException {

        private ResumeRejectedException(String message) {
            super(message);
        }
    }
}
