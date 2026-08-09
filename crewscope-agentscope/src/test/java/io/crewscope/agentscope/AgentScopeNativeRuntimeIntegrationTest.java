package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.crewscope.application.conversation.TaskIntentV1;
import io.crewscope.application.execution.ConversationCancelRequest;
import io.crewscope.application.execution.ConversationExecutionRequest;
import io.crewscope.application.execution.ConversationResumeRequest;
import io.crewscope.application.execution.AgentStateUnavailableException;
import io.crewscope.application.execution.AgentStatePreflight;
import io.crewscope.application.execution.ExecutionCancelResult;
import io.crewscope.application.execution.ExecutionEvent;
import io.crewscope.application.execution.ExecutionEventPayload;
import io.crewscope.application.execution.ExecutionFailureCategory;
import io.crewscope.application.execution.ExecutionHandle;
import io.crewscope.application.execution.ExecutionInterruptKind;
import io.crewscope.application.execution.ExecutionInterruptToken;
import io.crewscope.application.execution.RuntimeInvocationId;
import io.crewscope.application.execution.StructuredOutputSpec;
import io.crewscope.application.execution.PlatformExecutionContext;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.workspace.AgentProfileId;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Deterministic AgentScope 2.0.0 integration coverage for the production M2-I03 runtime. */
@Tag("integration")
class AgentScopeNativeRuntimeIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-09T05:30:00Z"), ZoneOffset.UTC);
    private static final String PROJECT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String OWNER_ID = "22222222-2222-4222-8222-222222222222";
    private static final String EXECUTOR_ID = "33333333-3333-4333-8333-333333333333";
    private static final String REVIEWER_ID = "44444444-4444-4444-8444-444444444444";

    @TempDir Path runtimeRoot;

    @Test
    void reusesOneAgentForMultiTurnConversationWhileIsolatingAnotherSession() {
        AgentProfileId profileId = AgentProfileId.generate();
        ScriptedModel model = new ScriptedModel("first answer", "second answer", "other answer");
        PersonalAgentFactory factory = factory(profileId, model, Toolkit::new);
        AgentRuntimeSession firstSession = AgentScopeRuntimeTestFixture.session(profileId, 2);
        AgentRuntimeSession otherSession = AgentScopeRuntimeTestFixture.session(profileId, 2);

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(factory, CLOCK)) {
            List<ExecutionEvent> first = invokeText(runtime, firstSession, "first question", 1);
            List<ExecutionEvent> second = invokeText(runtime, firstSession, "second question", 2);
            List<ExecutionEvent> other = invokeText(runtime, otherSession, "isolated question", 1);

            assertTextCompletion(first, "first answer");
            assertTextCompletion(second, "second answer");
            assertTextCompletion(other, "other answer");
            assertEquals(1, factory.cachedAgentCount());
            assertEquals(3, model.callCount());
            assertTrue(containsText(model.request(1), "first question"));
            assertTrue(containsText(model.request(1), "second question"));
            assertFalse(containsText(model.request(2), "first question"));
            assertTrue(containsText(model.request(2), "isolated question"));
        }
    }

    @Test
    void emitsTypedTaskIntentStructuredOutputAndCompletion() {
        AgentProfileId profileId = AgentProfileId.generate();
        ScriptedModel model = new ScriptedModel(
                structuredResponse("task-intent", validTaskIntent()));
        AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(profileId, 1);
        StructuredOutputSpec<TaskIntentV1> spec =
                new StructuredOutputSpec<>("task-intent/v1", TaskIntentV1.class);

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                factory(profileId, model, Toolkit::new), CLOCK)) {
            RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
            ConversationExecutionRequest request = executionRequest(
                    invocationId,
                    session,
                    AgentScopeRuntimeTestFixture.userMessage(session, "create task intent", 1),
                    Optional.of(spec));

            List<ExecutionEvent> events = collect(runtime.invokeConversation(request));

            assertEquals(3, events.size());
            assertInstanceOf(ExecutionEventPayload.Started.class, events.get(0).payload());
            ExecutionEventPayload.StructuredOutput<?> structured = assertInstanceOf(
                    ExecutionEventPayload.StructuredOutput.class, events.get(1).payload());
            TaskIntentV1 intent = assertInstanceOf(TaskIntentV1.class, structured.value());
            assertEquals("task-intent/v1", structured.spec().schemaId());
            assertEquals(PROJECT_ID, intent.workProjectId());
            assertInstanceOf(ExecutionEventPayload.Completed.class, events.get(2).payload());
        }
    }

    @Test
    void interruptsForClarificationAndResumesTheSameStructuredInvocation() {
        AgentProfileId profileId = AgentProfileId.generate();
        ClarificationTool clarificationTool = new ClarificationTool();
        ScriptedModel model = new ScriptedModel(
                clarificationResponse("clarification-call", clarificationTool.getName()),
                structuredResponse("task-after-answer", validTaskIntent()));
        AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(profileId, 4);
        StructuredOutputSpec<TaskIntentV1> spec =
                new StructuredOutputSpec<>("task-intent/v1", TaskIntentV1.class);

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                factory(profileId, model, () -> toolkitWith(clarificationTool)), CLOCK)) {
            RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
            List<ExecutionEvent> interrupted = collect(runtime.invokeConversation(executionRequest(
                    invocationId,
                    session,
                    AgentScopeRuntimeTestFixture.userMessage(
                            session, "prepare repository task", 1),
                    Optional.of(spec))));
            ExecutionEventPayload.Interrupted terminal = assertInstanceOf(
                    ExecutionEventPayload.Interrupted.class,
                    interrupted.get(interrupted.size() - 1).payload());

            assertEquals(ExecutionInterruptKind.CLARIFICATION, terminal.kind());
            assertFalse(terminal.token().value().contains("clarification-call"));
            Message answer = AgentScopeRuntimeTestFixture.userMessage(
                    session, "repository=crewscope-java", 2);
            UUID resumeCorrelationId = UUID.randomUUID();
            ConversationResumeRequest resume = new ConversationResumeRequest(
                    invocationId,
                    session,
                    terminal.token(),
                    UUID.randomUUID(),
                    answer,
                    resumeCorrelationId,
                    AgentScopeRuntimeTestFixture.platformContext(
                            session, invocationId, resumeCorrelationId));

            List<ExecutionEvent> resumed = collect(runtime.resumeConversation(resume));

            assertEquals(invocationId, resumed.get(0).invocationId());
            assertEquals(
                    io.crewscope.application.execution.ExecutionSegmentKind.RESUME,
                    assertInstanceOf(
                                    ExecutionEventPayload.Started.class,
                                    resumed.get(0).payload())
                            .segmentKind());
            assertInstanceOf(
                    ExecutionEventPayload.StructuredOutput.class, resumed.get(1).payload());
            assertInstanceOf(ExecutionEventPayload.Completed.class, resumed.get(2).payload());
            assertEquals("repository=crewscope-java", clarificationTool.answer);
            assertEquals(1, clarificationTool.executionCount.get());
            assertEquals(2, model.callCount());
            assertTrue(containsToolResult(model.request(1), "repository=crewscope-java"));

            assertThrows(
                    IllegalStateException.class,
                    () -> runtime.resumeConversation(resume));
        }
    }

    @Test
    void rejectsWrongInterruptTokenAndForeignSessionBeforeAgentScopeResume() {
        AgentProfileId profileId = AgentProfileId.generate();
        ClarificationTool clarificationTool = new ClarificationTool();
        ScriptedModel model = new ScriptedModel(
                clarificationResponse("pending", clarificationTool.getName()),
                structuredResponse("unused", validTaskIntent()));
        AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(profileId, 1);
        AgentRuntimeSession foreign = AgentScopeRuntimeTestFixture.session(profileId, 1);

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                factory(profileId, model, () -> toolkitWith(clarificationTool)), CLOCK)) {
            RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
            List<ExecutionEvent> events = collect(runtime.invokeConversation(executionRequest(
                    invocationId,
                    session,
                    AgentScopeRuntimeTestFixture.userMessage(session, "clarify", 1),
                    Optional.of(new StructuredOutputSpec<>(
                            "task-intent/v1", TaskIntentV1.class)))));
            ExecutionInterruptToken correctToken = assertInstanceOf(
                            ExecutionEventPayload.Interrupted.class,
                            events.get(events.size() - 1).payload())
                    .token();

            assertThrows(
                    IllegalArgumentException.class,
                    () -> {
                        UUID correlationId = UUID.randomUUID();
                        runtime.resumeConversation(new ConversationResumeRequest(
                                invocationId,
                                session,
                                new ExecutionInterruptToken("wrong-token"),
                                UUID.randomUUID(),
                                AgentScopeRuntimeTestFixture.userMessage(session, "answer", 2),
                                correlationId,
                                AgentScopeRuntimeTestFixture.platformContext(
                                        session, invocationId, correlationId)));
                    });
            assertThrows(
                    IllegalArgumentException.class,
                    () -> {
                        UUID correlationId = UUID.randomUUID();
                        runtime.resumeConversation(new ConversationResumeRequest(
                                invocationId,
                                foreign,
                                correctToken,
                                UUID.randomUUID(),
                                AgentScopeRuntimeTestFixture.userMessage(foreign, "answer", 1),
                                correlationId,
                                AgentScopeRuntimeTestFixture.platformContext(
                                        foreign, invocationId, correlationId)));
                    });
            assertEquals(1, model.callCount());
            assertEquals(0, clarificationTool.executionCount.get());
        }
    }

    @Test
    void allowsASecondDistinctClarificationOnTheSameInvocation() {
        AgentProfileId profileId = AgentProfileId.generate();
        ClarificationTool clarificationTool = new ClarificationTool();
        ScriptedModel model = new ScriptedModel(
                clarificationResponse("first-clarification", clarificationTool.getName()),
                clarificationResponse("second-clarification", clarificationTool.getName()),
                structuredResponse("task-after-two-answers", validTaskIntent()));
        AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(profileId, 1);
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                factory(profileId, model, () -> toolkitWith(clarificationTool)), CLOCK)) {
            List<ExecutionEvent> firstSegment = collect(runtime.invokeConversation(executionRequest(
                    invocationId,
                    session,
                    AgentScopeRuntimeTestFixture.userMessage(session, "prepare task", 1),
                    Optional.of(new StructuredOutputSpec<>(
                            "task-intent/v1", TaskIntentV1.class)))));
            ExecutionEventPayload.Interrupted firstInterrupt = assertInstanceOf(
                    ExecutionEventPayload.Interrupted.class,
                    firstSegment.get(firstSegment.size() - 1).payload());

            List<ExecutionEvent> secondSegment = collect(runtime.resumeConversation(
                    resumeRequest(invocationId, session, firstInterrupt.token(), "repository=crewscope-java", 2)));
            ExecutionEventPayload.Interrupted secondInterrupt = assertInstanceOf(
                    ExecutionEventPayload.Interrupted.class,
                    secondSegment.get(secondSegment.size() - 1).payload());

            assertNotEquals(firstInterrupt.token(), secondInterrupt.token());
            List<ExecutionEvent> finalSegment = collect(runtime.resumeConversation(
                    resumeRequest(invocationId, session, secondInterrupt.token(), "branch=main", 3)));

            assertInstanceOf(
                    ExecutionEventPayload.StructuredOutput.class,
                    finalSegment.get(finalSegment.size() - 2).payload());
            assertInstanceOf(
                    ExecutionEventPayload.Completed.class,
                    finalSegment.get(finalSegment.size() - 1).payload());
            assertEquals(2, clarificationTool.executionCount.get());
            assertEquals("branch=main", clarificationTool.answer);
        }
    }

    @Test
    void mapsRateLimitAndStructuredConversionFailuresToSafeTerminalEvents() {
        AgentProfileId rateProfile = AgentProfileId.generate();
        AgentRuntimeSession rateSession = AgentScopeRuntimeTestFixture.session(rateProfile, 1);
        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                factory(rateProfile, new RateLimitedModel(), Toolkit::new), CLOCK)) {
            List<ExecutionEvent> events = invokeText(
                    runtime, rateSession, "rate limited request", 1);
            ExecutionEventPayload.Failed failed = assertInstanceOf(
                    ExecutionEventPayload.Failed.class,
                    events.get(events.size() - 1).payload());
            assertEquals(ExecutionFailureCategory.MODEL_RATE_LIMITED, failed.failure().category());
            assertTrue(failed.failure().retryable());
            assertFalse(failed.failure().safeMessage().contains("provider-secret"));
        }

        AgentProfileId outputProfile = AgentProfileId.generate();
        ScriptedModel invalidOutput = new ScriptedModel(structuredResponse(
                "exploding-output", Map.of("value", "explode")));
        AgentRuntimeSession outputSession = AgentScopeRuntimeTestFixture.session(outputProfile, 1);
        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                factory(outputProfile, invalidOutput, Toolkit::new), CLOCK)) {
            RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
            List<ExecutionEvent> events = collect(runtime.invokeConversation(executionRequest(
                    invocationId,
                    outputSession,
                    AgentScopeRuntimeTestFixture.userMessage(outputSession, "explode", 1),
                    Optional.of(new StructuredOutputSpec<>(
                            "exploding-output/v1", ExplodingOutput.class)))));
            ExecutionEventPayload.Failed failed = assertInstanceOf(
                    ExecutionEventPayload.Failed.class,
                    events.get(events.size() - 1).payload());
            assertEquals(ExecutionFailureCategory.MODEL_OUTPUT_INVALID, failed.failure().category());
            assertEquals("STRUCTURED_OUTPUT_INVALID", failed.failure().runtimeCode().orElseThrow());
        }
    }

    @Test
    void preservesSanitizedProviderClassificationThroughTheObservationMiddleware() {
        AgentProfileId profileId = AgentProfileId.generate();
        AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(profileId, 1);
        Model providerFailure = new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.error(new HttpTransportException(
                        "provider-secret", 429, "provider-secret-response-body"));
            }

            @Override
            public String getModelName() {
                return "observed-rate-limited-model";
            }
        };
        PlatformAgentMiddlewareSet middlewares = new PlatformAgentMiddlewareSet(
                new PlatformRuntimeContextMiddleware(),
                new ProviderBindingSecurityMiddleware(),
                new PlatformAuditMiddleware(
                        ignored -> {},
                        ignored -> {},
                        AgentCallTraceContextProvider.none(),
                        CLOCK));
        PersonalAgentFactory observedFactory = new PersonalAgentFactory(
                (requestedId, version) -> new AgentScopePersonalAgentConfiguration(
                        requestedId,
                        version,
                        "observed-test-model",
                        Optional.empty(),
                        "You are the observed CrewScope Personal Agent.",
                        8,
                        1),
                ignored -> providerFailure,
                new InMemoryAgentStateStore(),
                Toolkit::new,
                runtimeRoot.resolve(profileId.toString()),
                middlewares);

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(observedFactory, CLOCK)) {
            List<ExecutionEvent> events = invokeText(runtime, session, "rate limited", 1);

            ExecutionEventPayload.Failed failed = assertInstanceOf(
                    ExecutionEventPayload.Failed.class,
                    events.get(events.size() - 1).payload());
            assertEquals(ExecutionFailureCategory.MODEL_RATE_LIMITED, failed.failure().category());
            assertEquals(Optional.of("MODEL_RATE_LIMITED"), failed.failure().runtimeCode());
            assertTrue(failed.failure().retryable());
            assertFalse(failed.failure().toString().contains("provider-secret"));
        }
    }

    @Test
    void closesTheInvocationWhenTheModelCompletesWithoutAResponse() {
        AgentProfileId profileId = AgentProfileId.generate();
        AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(profileId, 1);
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
        Model emptyModel = new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.empty();
            }

            @Override
            public String getModelName() {
                return "empty-model";
            }
        };

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                factory(profileId, emptyModel, Toolkit::new), CLOCK)) {
            List<ExecutionEvent> events = collect(runtime.invokeConversation(executionRequest(
                    invocationId,
                    session,
                    AgentScopeRuntimeTestFixture.userMessage(session, "empty response", 1),
                    Optional.empty())));

            assertEquals(1, events.stream().filter(ExecutionEvent::terminal).count());
            assertInstanceOf(
                    ExecutionEventPayload.Failed.class,
                    events.get(events.size() - 1).payload());
            assertEquals(
                    ExecutionCancelResult.ALREADY_TERMINAL,
                    runtime.cancel(cancelRequest(invocationId, session))
                            .toCompletableFuture()
                            .join());
        }
    }

    @Test
    void rejectsMissingProviderBindingThroughTheNativeRuntimeBeforeModelCall() {
        AgentProfileId profileId = AgentProfileId.generate();
        ScriptedModel model = new ScriptedModel("must not execute");
        AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(profileId, 1);
        PlatformAgentMiddlewareSet middlewares = new PlatformAgentMiddlewareSet(
                new PlatformRuntimeContextMiddleware(),
                new ProviderBindingSecurityMiddleware(),
                new PlatformAuditMiddleware(ignored -> {}, CLOCK));
        PersonalAgentFactory securedFactory = new PersonalAgentFactory(
                (requestedId, version) -> new AgentScopePersonalAgentConfiguration(
                        requestedId,
                        version,
                        "test-model",
                        Optional.empty(),
                        "You are the secured M2-I04 Personal Agent.",
                        8,
                        1),
                ignored -> model,
                new InMemoryAgentStateStore(),
                Toolkit::new,
                runtimeRoot.resolve(profileId.toString()),
                middlewares);
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
        UUID correlationId = UUID.randomUUID();
        PlatformExecutionContext base = AgentScopeRuntimeTestFixture.platformContext(
                session, invocationId, correlationId);
        PlatformExecutionContext missingBinding = new PlatformExecutionContext(
                base.scope(),
                base.workspaceType(),
                base.requestPrincipalId(),
                base.teamMemberId(),
                base.teamRoleKeys(),
                base.teamPermissions(),
                base.personalAgentPrincipalId(),
                base.agentProfileId(),
                base.agentProfileVersion(),
                base.conversationId(),
                base.conversationVisibility(),
                base.userParticipantId(),
                base.agentParticipantId(),
                base.runtimeSessionId(),
                base.agentScopeSessionKey(),
                base.invocationId(),
                base.correlationId(),
                Set.of(ProviderType.SOURCE_CODE),
                Map.of());
        ConversationExecutionRequest request = new ConversationExecutionRequest(
                invocationId,
                session,
                AgentScopeRuntimeTestFixture.userMessage(session, "read repository", 1),
                Optional.empty(),
                correlationId,
                missingBinding);

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                securedFactory, CLOCK)) {
            List<ExecutionEvent> events = collect(runtime.invokeConversation(request));

            ExecutionEventPayload.Failed failed = assertInstanceOf(
                    ExecutionEventPayload.Failed.class,
                    events.get(events.size() - 1).payload());
            assertEquals(ExecutionFailureCategory.AUTHORIZATION, failed.failure().category());
            assertEquals(
                    Optional.of("REQUIRED_PROVIDER_BINDING_MISSING"),
                    failed.failure().runtimeCode());
            assertEquals(0, model.callCount());
        }
    }

    @Test
    void failsClosedBeforeTheModelWhenAgentStatePreflightIsUnavailable() {
        AgentProfileId profileId = AgentProfileId.generate();
        ScriptedModel model = new ScriptedModel("must not execute");
        AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(profileId, 1);
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
        AtomicReference<io.crewscope.domain.conversation.AgentScopeSessionKey> checkedKey =
                new AtomicReference<>();

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                factoryWithPreflight(
                        profileId,
                        model,
                        Toolkit::new,
                        key -> {
                            checkedKey.set(key);
                            throw new AgentStateUnavailableException();
                        }),
                CLOCK)) {
            List<ExecutionEvent> events = collect(runtime.invokeConversation(executionRequest(
                    invocationId,
                    session,
                    AgentScopeRuntimeTestFixture.userMessage(session, "read state", 1),
                    Optional.empty())));

            ExecutionEventPayload.Failed failed = assertInstanceOf(
                    ExecutionEventPayload.Failed.class,
                    events.get(events.size() - 1).payload());
            assertEquals(ExecutionFailureCategory.STATE_UNAVAILABLE, failed.failure().category());
            assertTrue(failed.failure().retryable());
            assertEquals(Optional.of("AGENT_STATE_UNAVAILABLE"), failed.failure().runtimeCode());
            assertEquals(session.agentScopeKey(), checkedKey.get());
            assertEquals(0, model.callCount());
        }
    }

    @Test
    void runsStatePreflightAgainBeforeResumeAndDoesNotCallTheModelAfterFailure() {
        AgentProfileId profileId = AgentProfileId.generate();
        ClarificationTool clarificationTool = new ClarificationTool();
        ScriptedModel model = new ScriptedModel(
                clarificationResponse("m2-i05-clarification", clarificationTool.getName()));
        AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(profileId, 1);
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
        AtomicInteger preflightCalls = new AtomicInteger();

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                factoryWithPreflight(
                        profileId,
                        model,
                        () -> toolkitWith(clarificationTool),
                        ignored -> {
                            if (preflightCalls.incrementAndGet() == 2) {
                                throw new AgentStateUnavailableException();
                            }
                        }),
                CLOCK)) {
            List<ExecutionEvent> interrupted = collect(runtime.invokeConversation(executionRequest(
                    invocationId,
                    session,
                    AgentScopeRuntimeTestFixture.userMessage(session, "clarify", 1),
                    Optional.empty())));
            ExecutionEventPayload.Interrupted pending = assertInstanceOf(
                    ExecutionEventPayload.Interrupted.class,
                    interrupted.get(interrupted.size() - 1).payload());

            List<ExecutionEvent> resumed = collect(runtime.resumeConversation(resumeRequest(
                    invocationId, session, pending.token(), "answer", 2)));

            ExecutionEventPayload.Failed failed = assertInstanceOf(
                    ExecutionEventPayload.Failed.class,
                    resumed.get(resumed.size() - 1).payload());
            assertEquals(ExecutionFailureCategory.STATE_UNAVAILABLE, failed.failure().category());
            assertEquals(Optional.of("AGENT_STATE_UNAVAILABLE"), failed.failure().runtimeCode());
            assertEquals(2, preflightCalls.get());
            assertEquals(1, model.callCount());
        }
    }

    @Test
    void rejectsDuplicateInvocationBeforeASecondStatePreflightOrModelCall() {
        AgentProfileId profileId = AgentProfileId.generate();
        ScriptedModel model = new ScriptedModel("first response");
        AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(profileId, 1);
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
        AtomicInteger preflightCalls = new AtomicInteger();
        ConversationExecutionRequest request = executionRequest(
                invocationId,
                session,
                AgentScopeRuntimeTestFixture.userMessage(session, "only once", 1),
                Optional.empty());

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                factoryWithPreflight(
                        profileId,
                        model,
                        Toolkit::new,
                        ignored -> preflightCalls.incrementAndGet()),
                CLOCK)) {
            collect(runtime.invokeConversation(request));

            assertThrows(
                    IllegalStateException.class,
                    () -> runtime.invokeConversation(request));
            assertEquals(1, preflightCalls.get());
            assertEquals(1, model.callCount());
        }
    }

    @Test
    void runsQueuedSameSessionPreflightOnlyAfterThePreviousTurnLeavesTheFifoGate()
            throws Exception {
        AgentProfileId profileId = AgentProfileId.generate();
        ManualModel model = new ManualModel();
        AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(profileId, 1);
        AtomicInteger preflightCalls = new AtomicInteger();

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                factoryWithPreflight(
                        profileId,
                        model,
                        Toolkit::new,
                        ignored -> preflightCalls.incrementAndGet()),
                CLOCK)) {
            AsyncSubscriber first = subscribe(runtime.invokeConversation(executionRequest(
                    RuntimeInvocationId.generate(),
                    session,
                    AgentScopeRuntimeTestFixture.userMessage(session, "first queued turn", 1),
                    Optional.empty())));
            assertTrue(model.entered.await(2, TimeUnit.SECONDS));

            AsyncSubscriber second = subscribe(runtime.invokeConversation(executionRequest(
                    RuntimeInvocationId.generate(),
                    session,
                    AgentScopeRuntimeTestFixture.userMessage(session, "second queued turn", 2),
                    Optional.empty())));
            assertEquals(1, preflightCalls.get());

            model.complete("shared controlled response");
            first.await();
            second.await();
            assertEquals(2, preflightCalls.get());
        }
    }

    @Test
    void keepsTransportCancellationSeparateFromExplicitRuntimeCancellation() throws Exception {
        AgentProfileId profileId = AgentProfileId.generate();
        ManualModel model = new ManualModel();
        AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(profileId, 1);
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                factory(profileId, model, Toolkit::new), CLOCK)) {
            ExecutionHandle handle = runtime.invokeConversation(executionRequest(
                    invocationId,
                    session,
                    AgentScopeRuntimeTestFixture.userMessage(session, "wait", 1),
                    Optional.empty()));
            CancelAfterStartedSubscriber subscriber = new CancelAfterStartedSubscriber();
            handle.events().subscribe(subscriber);

            assertTrue(subscriber.started.await(2, TimeUnit.SECONDS));
            assertFalse(model.canceled.get());
            model.complete("finished after transport disconnect");
            assertTrue(model.terminated.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void explicitlyCancelsTheExactInvocationAndEmitsCanceledTerminal() {
        AgentProfileId profileId = AgentProfileId.generate();
        ManualModel model = new ManualModel();
        AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(profileId, 1);
        AgentRuntimeSession foreign = AgentScopeRuntimeTestFixture.session(profileId, 1);
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                factory(profileId, model, Toolkit::new), CLOCK)) {
            ExecutionHandle handle = runtime.invokeConversation(executionRequest(
                    invocationId,
                    session,
                    AgentScopeRuntimeTestFixture.userMessage(session, "cancel me", 1),
                    Optional.empty()));
            AsyncSubscriber subscriber = subscribe(handle);

            assertEquals(
                    ExecutionCancelResult.NOT_FOUND,
                    runtime.cancel(cancelRequest(invocationId, foreign))
                            .toCompletableFuture()
                            .join());
            assertEquals(
                    ExecutionCancelResult.ACCEPTED,
                    runtime.cancel(cancelRequest(invocationId, session))
                            .toCompletableFuture()
                            .join());
            model.complete("late response");
            List<ExecutionEvent> events = subscriber.await();

            ExecutionEventPayload.Canceled canceled = assertInstanceOf(
                    ExecutionEventPayload.Canceled.class,
                    events.get(events.size() - 1).payload());
            assertEquals("owner requested cancellation", canceled.reason());
            assertEquals(
                    ExecutionCancelResult.ALREADY_TERMINAL,
                    runtime.cancel(cancelRequest(invocationId, session))
                            .toCompletableFuture()
                            .join());
        }
    }

    @Test
    void evictsOnlyTheOldestLogicalTerminalAtTheConfiguredCapacity() {
        AgentProfileId profileId = AgentProfileId.generate();
        ScriptedModel model = new ScriptedModel("first", "second");
        AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(profileId, 1);

        try (AgentScopeNativeRuntime runtime = new AgentScopeNativeRuntime(
                factory(profileId, model, Toolkit::new), CLOCK, 1)) {
            RuntimeInvocationId first = RuntimeInvocationId.generate();
            RuntimeInvocationId second = RuntimeInvocationId.generate();
            collect(runtime.invokeConversation(executionRequest(
                    first,
                    session,
                    AgentScopeRuntimeTestFixture.userMessage(session, "first", 1),
                    Optional.empty())));
            collect(runtime.invokeConversation(executionRequest(
                    second,
                    session,
                    AgentScopeRuntimeTestFixture.userMessage(session, "second", 2),
                    Optional.empty())));

            assertEquals(
                    ExecutionCancelResult.NOT_FOUND,
                    runtime.cancel(cancelRequest(first, session)).toCompletableFuture().join());
            assertEquals(
                    ExecutionCancelResult.ALREADY_TERMINAL,
                    runtime.cancel(cancelRequest(second, session)).toCompletableFuture().join());
        }
    }

    private PersonalAgentFactory factory(
            AgentProfileId profileId, Model model, java.util.function.Supplier<Toolkit> toolkit) {
        return new PersonalAgentFactory(
                (requestedId, version) -> new AgentScopePersonalAgentConfiguration(
                        requestedId,
                        version,
                        "test-model",
                        Optional.empty(),
                        "You are the deterministic CrewScope M2-I03 Personal Agent.",
                        8,
                        1),
                ignored -> model,
                new InMemoryAgentStateStore(),
                toolkit,
                runtimeRoot.resolve(profileId.toString()));
    }

    private PersonalAgentFactory factoryWithPreflight(
            AgentProfileId profileId,
            Model model,
            java.util.function.Supplier<Toolkit> toolkit,
            AgentStatePreflight preflight) {
        PlatformAgentMiddlewareSet middlewares = new PlatformAgentMiddlewareSet(
                new PlatformRuntimeContextMiddleware(),
                new ProviderBindingSecurityMiddleware(),
                new PlatformAuditMiddleware(ignored -> {}, CLOCK),
                new AgentStatePreflightMiddleware(preflight));
        return new PersonalAgentFactory(
                (requestedId, version) -> new AgentScopePersonalAgentConfiguration(
                        requestedId,
                        version,
                        "test-model",
                        Optional.empty(),
                        "You are the deterministic CrewScope M2-I05 Personal Agent.",
                        8,
                        1),
                ignored -> model,
                new InMemoryAgentStateStore(),
                toolkit,
                runtimeRoot.resolve(profileId.toString()),
                middlewares);
    }

    private static ConversationExecutionRequest executionRequest(
            RuntimeInvocationId invocationId,
            AgentRuntimeSession session,
            Message message,
            Optional<StructuredOutputSpec<?>> structuredOutput) {
        UUID correlationId = UUID.randomUUID();
        return new ConversationExecutionRequest(
                invocationId,
                session,
                message,
                structuredOutput,
                correlationId,
                AgentScopeRuntimeTestFixture.platformContext(
                        session, invocationId, correlationId));
    }

    private static ConversationCancelRequest cancelRequest(
            RuntimeInvocationId invocationId, AgentRuntimeSession session) {
        UUID correlationId = UUID.randomUUID();
        return new ConversationCancelRequest(
                invocationId,
                session,
                "owner requested cancellation",
                correlationId,
                AgentScopeRuntimeTestFixture.platformContext(
                        session, invocationId, correlationId));
    }

    private static ConversationResumeRequest resumeRequest(
            RuntimeInvocationId invocationId,
            AgentRuntimeSession session,
            ExecutionInterruptToken token,
            String answer,
            long sequence) {
        UUID correlationId = UUID.randomUUID();
        return new ConversationResumeRequest(
                invocationId,
                session,
                token,
                UUID.randomUUID(),
                AgentScopeRuntimeTestFixture.userMessage(session, answer, sequence),
                correlationId,
                AgentScopeRuntimeTestFixture.platformContext(
                        session, invocationId, correlationId));
    }

    private static List<ExecutionEvent> invokeText(
            AgentScopeNativeRuntime runtime,
            AgentRuntimeSession session,
            String text,
            long sequence) {
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
        return collect(runtime.invokeConversation(executionRequest(
                invocationId,
                session,
                AgentScopeRuntimeTestFixture.userMessage(session, text, sequence),
                Optional.empty())));
    }

    private static List<ExecutionEvent> collect(ExecutionHandle handle) {
        return subscribe(handle).await();
    }

    private static AsyncSubscriber subscribe(ExecutionHandle handle) {
        AsyncSubscriber subscriber = new AsyncSubscriber();
        handle.events().subscribe(subscriber);
        return subscriber;
    }

    private static void assertTextCompletion(List<ExecutionEvent> events, String expectedText) {
        assertTrue(events.size() >= 3);
        assertInstanceOf(ExecutionEventPayload.Started.class, events.get(0).payload());
        String text = events.stream()
                .map(ExecutionEvent::payload)
                .filter(ExecutionEventPayload.TextDelta.class::isInstance)
                .map(ExecutionEventPayload.TextDelta.class::cast)
                .map(ExecutionEventPayload.TextDelta::text)
                .reduce("", String::concat);
        assertEquals(expectedText, text);
        assertInstanceOf(
                ExecutionEventPayload.Completed.class,
                events.get(events.size() - 1).payload());
    }

    private static boolean containsText(List<Msg> messages, String expected) {
        return messages.stream().map(Msg::getTextContent).anyMatch(text -> text.contains(expected));
    }

    private static boolean containsToolResult(List<Msg> messages, String expected) {
        return messages.stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .flatMap(result -> result.getOutput().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .anyMatch(text -> text.contains(expected));
    }

    private static Toolkit toolkitWith(ToolBase tool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        return toolkit;
    }

    private static Map<String, Object> validTaskIntent() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", "1");
        output.put("objective", "Implement repository conversation workflow");
        output.put("acceptanceCriteria", List.of("Repository is explicit", "Review is auditable"));
        output.put("workProjectId", PROJECT_ID);
        output.put("ownerMemberId", OWNER_ID);
        output.put("executorPrincipalId", EXECUTOR_ID);
        output.put("gateReviewerMemberId", REVIEWER_ID);
        return output;
    }

    private static ChatResponse structuredResponse(
            String toolCallId, Map<String, Object> response) {
        return toolResponse(toolCallId, "generate_response", Map.of("response", response));
    }

    private static ChatResponse clarificationResponse(String toolCallId, String toolName) {
        return toolResponse(
                toolCallId,
                toolName,
                Map.of("request", Map.of(
                        "summary", "Repository is required",
                        "question", "Which repository should be changed?")));
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
                .usage(new ChatUsage(10, 4, 0.01))
                .finishReason("tool_calls")
                .build();
    }

    private static final class ClarificationTool extends ToolBase {

        private final AtomicInteger executionCount = new AtomicInteger();
        private volatile String answer;

        private ClarificationTool() {
            super(ToolBase.builder()
                    .name("request_clarification")
                    .description("Pause for one CrewScope clarification answer")
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "request", Map.of("type", "object"),
                                    "answer", Map.of("type", "string")),
                            "required", List.of("request")))
                    .readOnly(true)
                    .concurrencySafe(true));
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("Clarification is required"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executionCount.incrementAndGet();
            answer = String.valueOf(param.getInput().get("answer"));
            return Mono.just(ToolResultBlock.text("clarification_answer=" + answer));
        }
    }

    private record ExplodingOutput(String value) {
        private ExplodingOutput {
            if ("explode".equals(value)) {
                throw new IllegalArgumentException("exploding structured output");
            }
        }
    }

    private static final class RateLimitedModel implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new RateLimitedException("provider-secret 429 rate limit"));
        }

        @Override
        public String getModelName() {
            return "rate-limited-model";
        }
    }

    private static final class RateLimitedException extends RuntimeException {
        private RateLimitedException(String message) {
            super(message);
        }
    }

    private static final class ManualModel implements Model {

        private final Sinks.One<ChatResponse> response = Sinks.one();
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch terminated = new CountDownLatch(1);

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            entered.countDown();
            return response.asMono()
                    .doOnCancel(() -> {
                        canceled.set(true);
                        terminated.countDown();
                    })
                    .doOnSuccess(ignored -> terminated.countDown())
                    .flux();
        }

        @Override
        public String getModelName() {
            return "manual-model";
        }

        private void complete(String text) {
            Sinks.EmitResult result = response.tryEmitValue(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text(text).build()))
                    .usage(new ChatUsage(10, 4, 0.01))
                    .finishReason("stop")
                    .build());
            assertTrue(result.isSuccess() || result == Sinks.EmitResult.FAIL_CANCELLED);
        }
    }

    private static final class AsyncSubscriber implements Flow.Subscriber<ExecutionEvent> {

        private final List<ExecutionEvent> events = new ArrayList<>();
        private final CompletableFuture<List<ExecutionEvent>> completion = new CompletableFuture<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public synchronized void onNext(ExecutionEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            completion.completeExceptionally(throwable);
        }

        @Override
        public synchronized void onComplete() {
            completion.complete(List.copyOf(events));
        }

        private List<ExecutionEvent> await() {
            try {
                return completion.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception exception) {
                throw new AssertionError("Execution stream did not complete", exception);
            }
        }
    }

    private static final class CancelAfterStartedSubscriber
            implements Flow.Subscriber<ExecutionEvent> {

        private final CountDownLatch started = new CountDownLatch(1);
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(ExecutionEvent item) {
            assertInstanceOf(ExecutionEventPayload.Started.class, item.payload());
            subscription.cancel();
            started.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
            throw new AssertionError(throwable);
        }

        @Override
        public void onComplete() {}
    }
}
