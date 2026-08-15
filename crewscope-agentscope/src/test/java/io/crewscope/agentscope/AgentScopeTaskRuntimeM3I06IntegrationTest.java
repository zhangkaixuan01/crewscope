package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.util.JsonUtils;
import io.crewscope.agentscope.task.AgentScopeTaskPlanAdapter;
import io.crewscope.agentscope.task.AgentScopeTaskPlanningSnapshotMapper;
import io.crewscope.agentscope.task.AgentScopeTaskRuntime;
import io.crewscope.agentscope.task.AgentScopeTaskRuntimeProfile;
import io.crewscope.agentscope.task.ControlledTaskPlanParser;
import io.crewscope.agentscope.task.ControlledTaskToolkitFactory;
import io.crewscope.agentscope.task.TaskAgentConfiguration;
import io.crewscope.agentscope.task.TaskAgentFactory;
import io.crewscope.application.execution.TaskExecutionControlAction;
import io.crewscope.application.execution.TaskExecutionControlRequest;
import io.crewscope.application.execution.TaskExecutionControlResult;
import io.crewscope.application.execution.TaskExecutionEvent;
import io.crewscope.application.execution.TaskExecutionEventPayload;
import io.crewscope.application.execution.TaskExecutionHandle;
import io.crewscope.application.execution.TaskExecutionRequest;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.task.TaskTokenExecutionContext;
import io.crewscope.application.execution.TaskExecutionTerminalStatus;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunSegment;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskTokenGrantScope;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** End-to-end controlled Model tests for M3-I06 planning, execution, budgets and controls. */
class AgentScopeTaskRuntimeM3I06IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-15T11:00:00Z"), ZoneOffset.UTC);
    private static final String VALID_PLAN =
            """
            # Controlled Task Plan

            - `inspect` | ANALYSIS | Inspect input | deps=- | capabilities=PLAN | tools=fixture.inspect | critical=true
            - `execute` | IMPLEMENTATION | Produce result | deps=inspect | capabilities=PLAN | tools=fixture.execute | critical=true
            - `validate` | VALIDATION | Validate result | deps=execute | capabilities=STRUCTURED_OUTPUT | tools=fixture.validate | critical=true
            """;

    @TempDir Path runtimeRoot;

    @Test
    void repairsPlanPublishesTodoAndResumesIntoControlledStepOrchestration() throws Exception {
        String invalidPlan = VALID_PLAN.replace("deps=execute", "deps=missing");
        ScriptedModel model = new ScriptedModel(
                toolResponse("enter", "plan_enter", Map.of()),
                toolResponse("todo", "todo_write", Map.of("todos", todos())),
                toolResponse("invalid", "validate_task_plan", Map.of("content", invalidPlan)),
                toolResponse("valid", "validate_task_plan", Map.of("content", VALID_PLAN)),
                toolResponse("write", "plan_write", Map.of("content", VALID_PLAN)),
                toolResponse("exit", "plan_exit", Map.of("summary", "Controlled plan ready")),
                toolResponse("inspect", "fixture.inspect", Map.of("input", "fixture")),
                toolResponse("execute", "fixture.execute", Map.of("input", "fixture")),
                toolResponse("validate", "fixture.validate", Map.of("input", "fixture")),
                textResponse("Controlled fixture completed"));
        RuntimeFixture fixture = RuntimeFixture.create(model, new PolicyBudget(10_000, 20, 20, 30));
        AtomicReference<AgentScopeTaskPlanAdapter.Candidate> published = new AtomicReference<>();
        try (AgentScopeTaskRuntime runtime = runtime(fixture, candidate -> {
            published.set(candidate);
            return PlanVersionId.generate();
        })) {
            List<TaskExecutionEvent> planning = collect(runtime.executeTask(
                    fixture.request(AgentRunSegmentKind.INVOKE, 1, Optional.empty())));

            assertEquals(TaskExecutionTerminalStatus.INTERRUPTED,
                    planning.get(planning.size() - 1).payload().terminalStatus().orElseThrow());
            assertTrue(planning.stream().anyMatch(event ->
                    event.payload() instanceof TaskExecutionEventPayload.PlanChanged));
            assertEquals(3, published.get().plan().steps().size());
            assertEquals(3, published.get().todos().size());
            assertEquals("[inspect] Inspect input", published.get().todos().get(0).content());
            assertTrue(model.callCount() >= 6);
        }

        // Recreate both Task runtime and HarnessAgent. The pending plan approval and Todo list are
        // recovered from the stable AgentScope (userId, sessionId) slot.
        PlanVersion selectedPlan = mock(PlanVersion.class);
        when(selectedPlan.steps()).thenReturn(published.get().plan().steps());
        TaskExecutionRuntimeFacts resumeFacts = fixture.facts(
                AgentRunSegmentKind.RESUME, 2, Optional.of(selectedPlan));
        try (AgentScopeTaskRuntime recovered = runtime(fixture, candidate -> {
            throw new AssertionError("Resume must not republish the already selected plan");
        })) {
            TaskExecutionControlRequest resume = fixture.control(
                    resumeFacts, TaskExecutionControlAction.RESUME, "Approve controlled plan");
            assertEquals(
                    TaskExecutionControlResult.ACCEPTED,
                    recovered.controlTask(resume).toCompletableFuture().get(2, TimeUnit.SECONDS));

            List<TaskExecutionEvent> execution = collect(recovered.executeTask(
                    new TaskExecutionRequest(resumeFacts, UUID.randomUUID())));

            assertEquals(TaskExecutionTerminalStatus.COMPLETED,
                    execution.get(execution.size() - 1).payload().terminalStatus().orElseThrow());
            List<String> startedTools = execution.stream()
                    .map(TaskExecutionEvent::payload)
                    .filter(TaskExecutionEventPayload.ToolStarted.class::isInstance)
                    .map(TaskExecutionEventPayload.ToolStarted.class::cast)
                    .map(TaskExecutionEventPayload.ToolStarted::toolName)
                    .toList();
            assertTrue(startedTools.containsAll(List.of(
                    "fixture.inspect", "fixture.execute", "fixture.validate")));
            assertTrue(execution.stream().anyMatch(event ->
                    event.payload() instanceof TaskExecutionEventPayload.UsageReported));
        }
    }

    @Test
    void resumesPendingPlanOnTheSameRuntimeWithANewerFencedLease() throws Exception {
        ScriptedModel model = new ScriptedModel(
                toolResponse("enter", "plan_enter", Map.of()),
                toolResponse("write", "plan_write", Map.of("content", VALID_PLAN)),
                toolResponse("exit", "plan_exit", Map.of("summary", "Controlled plan ready")),
                toolResponse("inspect", "fixture.inspect", Map.of("input", "fixture")),
                toolResponse("execute", "fixture.execute", Map.of("input", "fixture")),
                toolResponse("validate", "fixture.validate", Map.of("input", "fixture")),
                textResponse("Controlled fixture completed"));
        RuntimeFixture fixture = RuntimeFixture.create(
                model, new PolicyBudget(10_000, 20, 20, 30));
        AtomicReference<AgentScopeTaskPlanAdapter.Candidate> published = new AtomicReference<>();
        try (AgentScopeTaskRuntime runtime = runtime(fixture, candidate -> {
            published.set(candidate);
            return PlanVersionId.generate();
        })) {
            List<TaskExecutionEvent> planning = collect(runtime.executeTask(
                    fixture.request(AgentRunSegmentKind.INVOKE, 1, Optional.empty())));
            assertEquals(TaskExecutionTerminalStatus.INTERRUPTED,
                    planning.get(planning.size() - 1).payload().terminalStatus().orElseThrow());

            PlanVersion selectedPlan = mock(PlanVersion.class);
            when(selectedPlan.steps()).thenReturn(published.get().plan().steps());
            TaskExecutionRuntimeFacts resumeFacts = fixture.facts(
                    AgentRunSegmentKind.RESUME, 2, Optional.of(selectedPlan));
            when(resumeFacts.lease().id()).thenReturn(ExecutionLeaseId.generate());
            when(resumeFacts.lease().fencingToken()).thenReturn(fixture.fencingToken.next());

            assertEquals(
                    TaskExecutionControlResult.ACCEPTED,
                    runtime.controlTask(fixture.control(
                                    resumeFacts,
                                    TaskExecutionControlAction.RESUME,
                                    "Approve on the new ownership epoch"))
                            .toCompletableFuture()
                            .get(2, TimeUnit.SECONDS));
            List<TaskExecutionEvent> execution = collect(runtime.executeTask(
                    new TaskExecutionRequest(resumeFacts, UUID.randomUUID())));

            assertEquals(TaskExecutionTerminalStatus.COMPLETED,
                    execution.get(execution.size() - 1).payload().terminalStatus().orElseThrow());
        }
    }

    @Test
    void enforcesModelBudgetBeforeAnUnboundedAgentLoop() throws Exception {
        ScriptedModel model = new ScriptedModel(
                toolResponse("enter", "plan_enter", Map.of()),
                textResponse("second model call must not complete"));
        RuntimeFixture fixture = RuntimeFixture.create(model, new PolicyBudget(10_000, 1, 10, 30));
        try (AgentScopeTaskRuntime runtime = runtime(fixture, ignored -> PlanVersionId.generate())) {
            List<TaskExecutionEvent> events = collect(runtime.executeTask(
                    fixture.request(AgentRunSegmentKind.INVOKE, 1, Optional.empty())));

            TaskExecutionEventPayload.Failed failed = assertInstanceOf(
                    TaskExecutionEventPayload.Failed.class,
                    events.get(events.size() - 1).payload());
            assertEquals(Optional.of("MODEL_CALL_BUDGET_EXCEEDED"),
                    failed.failure().runtimeCode());
        }
    }

    @Test
    void propagatesPauseAndCancelToTheAgentScopeSessionSafePoint() throws Exception {
        assertControlTerminal(
                TaskExecutionControlAction.PAUSE,
                TaskExecutionTerminalStatus.PAUSED,
                TaskExecutionEventPayload.Paused.class);
        assertControlTerminal(
                TaskExecutionControlAction.CANCEL,
                TaskExecutionTerminalStatus.CANCELED,
                TaskExecutionEventPayload.Canceled.class);
    }

    @Test
    void publishesOnlyTheCapabilitiesActuallyImplementedByTheM3Runtime() {
        assertTrue(AgentScopeTaskRuntimeProfile.capabilities()
                .supports(RuntimeCapability.TASK_EXECUTION));
        assertTrue(AgentScopeTaskRuntimeProfile.capabilities().supports(RuntimeCapability.PLAN));
        assertTrue(AgentScopeTaskRuntimeProfile.capabilities()
                .supports(RuntimeCapability.PAUSE_RESUME));
        assertTrue(!AgentScopeTaskRuntimeProfile.capabilities()
                .supports(RuntimeCapability.EXTERNAL_TOOL));
    }

    private void assertControlTerminal(
            TaskExecutionControlAction action,
            TaskExecutionTerminalStatus expectedStatus,
            Class<? extends TaskExecutionEventPayload> expectedPayload) throws Exception {
        DelayedModel model = new DelayedModel(textResponse("delayed result"));
        RuntimeFixture fixture = RuntimeFixture.create(model, new PolicyBudget(10_000, 5, 5, 10));
        try (AgentScopeTaskRuntime runtime = runtime(fixture, ignored -> PlanVersionId.generate())) {
            TaskExecutionRuntimeFacts facts = fixture.facts(
                    AgentRunSegmentKind.INVOKE, 1, Optional.empty());
            TaskExecutionHandle handle = runtime.executeTask(
                    new TaskExecutionRequest(facts, UUID.randomUUID()));
            UUID controlRequestId = UUID.randomUUID();
            assertEquals(TaskExecutionControlResult.ACCEPTED,
                    runtime.controlTask(new TaskExecutionControlRequest(
                                    facts,
                                    action,
                                    controlRequestId,
                                    action.name().toLowerCase(),
                                    UUID.randomUUID()))
                            .toCompletableFuture()
                            .get(2, TimeUnit.SECONDS));

            List<TaskExecutionEvent> events = collect(handle);

            assertEquals(expectedStatus,
                    events.get(events.size() - 1).payload().terminalStatus().orElseThrow());
            TaskExecutionEventPayload terminal = events.get(events.size() - 1).payload();
            assertInstanceOf(expectedPayload, terminal);
            if (terminal instanceof TaskExecutionEventPayload.Paused paused) {
                assertEquals(controlRequestId.toString(), paused.token().value());
            }
        }
    }

    private AgentScopeTaskRuntime runtime(
            RuntimeFixture fixture,
            java.util.function.Function<AgentScopeTaskPlanAdapter.Candidate, PlanVersionId> publisher) {
        ControlledTaskPlanParser parser = new ControlledTaskPlanParser();
        TaskAgentFactory factory = new TaskAgentFactory(
                (id, version) -> new TaskAgentConfiguration(
                        id,
                        version,
                        "scripted",
                        Optional.empty(),
                        "Use controlled plans, Todo cognition and Fixture Tools only.",
                        30,
                        1),
                ignored -> fixture.model,
                fixture.stateStore,
                new ControlledTaskToolkitFactory(parser),
                runtimeRoot.resolve(UUID.randomUUID().toString()));
        return new AgentScopeTaskRuntime(
                factory,
                new AgentScopeTaskPlanningSnapshotMapper(),
                new AgentScopeTaskPlanAdapter(parser),
                (facts, candidate) -> publisher.apply(candidate),
                CLOCK);
    }

    private static List<TaskExecutionEvent> collect(TaskExecutionHandle handle) throws Exception {
        CollectingSubscriber subscriber = new CollectingSubscriber();
        handle.events().subscribe(subscriber);
        return subscriber.result.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
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

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(10, 4, 0.01))
                .finishReason("stop")
                .build();
    }

    private static List<Map<String, Object>> todos() {
        return List.of(
                todo("[inspect] Inspect input", "completed", "high"),
                todo("[execute] Produce result", "in_progress", "medium"),
                todo("[validate] Validate result", "pending", "low"));
    }

    private static Map<String, Object> todo(String content, String status, String priority) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("content", content);
        item.put("status", status);
        item.put("priority", priority);
        return item;
    }

    private static final class DelayedModel implements Model {

        private final ChatResponse response;

        private DelayedModel(ChatResponse response) {
            this.response = response;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(response).delayElements(Duration.ofMillis(300));
        }

        @Override
        public String getModelName() {
            return "delayed-controlled-model";
        }
    }

    private static final class CollectingSubscriber
            implements Flow.Subscriber<TaskExecutionEvent> {

        private final List<TaskExecutionEvent> events = new ArrayList<>();
        private final CompletableFuture<List<TaskExecutionEvent>> result = new CompletableFuture<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(TaskExecutionEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(List.copyOf(events));
        }
    }

    private static final class RuntimeFixture {

        private final Model model;
        private final InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        private final AgentProfileId profileId = AgentProfileId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final AgentRunId runId = AgentRunId.generate();
        private final ExecutionLeaseId leaseId = ExecutionLeaseId.generate();
        private final FencingToken fencingToken = FencingToken.initial();
        private final AgentScopeSessionKey sessionKey = new AgentScopeSessionKey(
                "crewscope:v1:user:m3-i06",
                "crewscope:v1:session:" + executionId);
        private final PolicyBudget budget;

        private RuntimeFixture(Model model, PolicyBudget budget) {
            this.model = model;
            this.budget = budget;
        }

        private static RuntimeFixture create(Model model, PolicyBudget budget) {
            return new RuntimeFixture(model, budget);
        }

        private TaskExecutionRequest request(
                AgentRunSegmentKind kind, long sequence, Optional<PlanVersion> plan) {
            return new TaskExecutionRequest(facts(kind, sequence, plan), UUID.randomUUID());
        }

        private TaskExecutionRuntimeFacts facts(
                AgentRunSegmentKind kind, long sequence, Optional<PlanVersion> plan) {
            TaskExecutionRuntimeFacts facts = mock(TaskExecutionRuntimeFacts.class);
            TaskAgentRuntimeSession session = mock(TaskAgentRuntimeSession.class);
            when(session.canInvoke()).thenReturn(true);
            when(session.agentProfileId()).thenReturn(profileId);
            when(session.agentProfileVersion()).thenReturn(7L);
            when(session.agentScopeKey()).thenReturn(sessionKey);
            when(facts.runtimeSession()).thenReturn(session);

            PolicySnapshot policy = mock(PolicySnapshot.class);
            when(policy.agentProfileId()).thenReturn(profileId);
            when(policy.agentProfileVersion()).thenReturn(7L);
            when(policy.budget()).thenReturn(budget);
            when(facts.policySnapshot()).thenReturn(policy);
            when(facts.planVersion()).thenReturn(plan);
            when(facts.stepExecution()).thenReturn(Optional.empty());
            Task task = mock(Task.class);
            when(task.brief()).thenReturn(new TaskBrief(
                    "Deliver the controlled runtime task",
                    List.of("Publish a validated plan")));
            when(facts.task()).thenReturn(task);
            TaskTokenGrantScope tokenScope = mock(TaskTokenGrantScope.class);
            TaskTokenExecutionContext authorization = mock(TaskTokenExecutionContext.class);
            when(authorization.scope()).thenReturn(tokenScope);
            when(facts.authorization()).thenReturn(authorization);

            TaskExecution execution = mock(TaskExecution.class);
            when(execution.id()).thenReturn(executionId);
            when(execution.attempt()).thenReturn(1);
            when(execution.status()).thenReturn(TaskExecutionStatus.RUNNING);
            when(facts.execution()).thenReturn(execution);

            AgentRunSegment segment = mock(AgentRunSegment.class);
            when(segment.kind()).thenReturn(kind);
            when(segment.sequence()).thenReturn(sequence);
            AgentRun run = mock(AgentRun.class);
            when(run.id()).thenReturn(runId);
            when(run.currentSegment()).thenReturn(segment);
            when(facts.agentRun()).thenReturn(run);

            ExecutionLease lease = mock(ExecutionLease.class);
            when(lease.id()).thenReturn(leaseId);
            when(lease.fencingToken()).thenReturn(fencingToken);
            when(facts.lease()).thenReturn(lease);
            return facts;
        }

        private TaskExecutionControlRequest control(
                TaskExecutionRuntimeFacts facts,
                TaskExecutionControlAction action,
                String reason) {
            return new TaskExecutionControlRequest(
                    facts, action, UUID.randomUUID(), reason, UUID.randomUUID());
        }
    }
}
