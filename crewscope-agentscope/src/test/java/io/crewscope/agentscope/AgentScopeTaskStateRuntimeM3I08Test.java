package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.crewscope.agentscope.task.AgentScopeTaskPlanAdapter;
import io.crewscope.agentscope.task.AgentScopeTaskPlanningSnapshotMapper;
import io.crewscope.agentscope.task.AgentScopeTaskRuntime;
import io.crewscope.agentscope.task.ControlledTaskPlanParser;
import io.crewscope.agentscope.task.ControlledTaskToolkitFactory;
import io.crewscope.agentscope.task.TaskAgentConfiguration;
import io.crewscope.agentscope.task.TaskAgentFactory;
import io.crewscope.application.execution.AgentStateUnavailableException;
import io.crewscope.application.execution.TaskAgentStateCheckpointCommand;
import io.crewscope.application.execution.TaskAgentStateCheckpointResult;
import io.crewscope.application.execution.TaskAgentStateRecoveryCommand;
import io.crewscope.application.execution.TaskAgentStateRecoveryResult;
import io.crewscope.application.execution.TaskAgentStateSafePoint;
import io.crewscope.application.execution.TaskAgentStateSnapshotService;
import io.crewscope.application.execution.TaskExecutionRequest;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.task.TaskTokenExecutionContext;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunSegment;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.AgentStateSnapshotId;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.RuntimeArtifactId;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** AgentScope hot-state and safe-boundary contract tests for the M3-I08 durable adapter. */
class AgentScopeTaskStateRuntimeM3I08Test {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-15T12:00:00Z"), ZoneOffset.UTC);

    @TempDir Path runtimeRoot;

    @Test
    void checkpointsTheCompleteHotStateWithItsPinnedIdentity() {
        Fixture fixture = new Fixture();
        AgentState hotState = fixture.state("complete-hot-state");
        fixture.save(hotState);
        CapturingSnapshotService snapshots = new CapturingSnapshotService();

        try (AgentScopeTaskRuntime runtime = fixture.runtime(snapshots)) {
            for (TaskAgentStateSafePoint safePoint : TaskAgentStateSafePoint.values()) {
                TaskAgentStateCheckpointResult result = runtime.checkpointState(
                        fixture.facts, 1, 9, safePoint);

                TaskAgentStateCheckpointCommand command = snapshots.checkpoint.get();
                AgentState captured = AgentState.fromJsonString(command.agentStateJson());
                assertEquals("complete-hot-state", captured.getContext().get(0).getTextContent());
                assertEquals(fixture.sessionKey.userId(), command.identity().userId());
                assertEquals(fixture.sessionKey.sessionId(), command.identity().sessionId());
                assertEquals(command.identity().agentName(), command.identity().agentId());
                assertEquals(safePoint, result.safePoint());
            }
        }
    }

    @Test
    void recoveryRebuildsAnEmptyOrStaleHotStateSlot() {
        Fixture empty = new Fixture();
        CapturingSnapshotService emptySnapshots = new CapturingSnapshotService();
        emptySnapshots.recoveredState.set(empty.state("recovered-empty-slot"));
        try (AgentScopeTaskRuntime runtime = empty.runtime(emptySnapshots)) {
            runtime.recoverState(empty.facts, 10);
            assertEquals("recovered-empty-slot", empty.load().getContext().get(0).getTextContent());
        }

        Fixture stale = new Fixture();
        stale.save(stale.state("stale-or-damaged-hot-state"));
        CapturingSnapshotService staleSnapshots = new CapturingSnapshotService();
        staleSnapshots.recoveredState.set(stale.state("trusted-durable-state"));
        try (AgentScopeTaskRuntime runtime = stale.runtime(staleSnapshots)) {
            runtime.recoverState(stale.facts, 10);
            assertEquals("trusted-durable-state", stale.load().getContext().get(0).getTextContent());
        }
    }

    @Test
    void rejectsCrossSessionRecoveryWithoutReplacingTheHotSlot() {
        Fixture fixture = new Fixture();
        fixture.save(fixture.state("trusted-existing-state"));
        CapturingSnapshotService snapshots = new CapturingSnapshotService();
        snapshots.recoveredState.set(AgentState.builder()
                .userId("foreign-user")
                .sessionId("foreign-session")
                .addMessage(new UserMessage("foreign-state"))
                .build());

        try (AgentScopeTaskRuntime runtime = fixture.runtime(snapshots)) {
            assertThrows(AgentStateUnavailableException.class,
                    () -> runtime.recoverState(fixture.facts, 10));
            assertEquals("trusted-existing-state",
                    fixture.load().getContext().get(0).getTextContent());
        }
    }

    @Test
    void rejectsCheckpointAndRecoveryWhileASegmentIsRunning() {
        Fixture fixture = new Fixture();
        fixture.save(fixture.state("safe-before-run"));
        CapturingSnapshotService snapshots = new CapturingSnapshotService();
        snapshots.recoveredState.set(fixture.state("should-not-be-written"));

        try (AgentScopeTaskRuntime runtime = fixture.runtime(snapshots)) {
            // The runtime owns the AgentScope subscription, so creating the handle starts the
            // delayed Segment even when no HTTP/SSE subscriber has attached yet.
            runtime.executeTask(new TaskExecutionRequest(fixture.facts, UUID.randomUUID()));

            assertThrows(IllegalStateException.class, () -> runtime.checkpointState(
                    fixture.facts, 1, 1, TaskAgentStateSafePoint.PERIODIC));
            assertThrows(IllegalStateException.class,
                    () -> runtime.recoverState(fixture.facts, 10));
            assertTrue(snapshots.checkpoint.get() == null);
            assertTrue(snapshots.recovery.get() == null);
        }
    }

    @Test
    void legacyConstructorFailsClosedForSnapshotOperations() {
        Fixture fixture = new Fixture();
        fixture.save(fixture.state("legacy-hot-state"));
        try (AgentScopeTaskRuntime runtime = fixture.legacyRuntime()) {
            assertThrows(IllegalStateException.class, () -> runtime.checkpointState(
                    fixture.facts, 1, 1, TaskAgentStateSafePoint.SHUTDOWN));
            assertThrows(IllegalStateException.class,
                    () -> runtime.recoverState(fixture.facts, 10));
        }
    }

    private final class Fixture {

        private final InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        private final AgentProfileId profileId = AgentProfileId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final AgentRunId runId = AgentRunId.generate();
        private final AgentScopeSessionKey sessionKey = new AgentScopeSessionKey(
                "crewscope:v1:user:m3-i08", "crewscope:v1:session:" + executionId);
        private final TaskExecutionRuntimeFacts facts = facts();

        private TaskExecutionRuntimeFacts facts() {
            TaskExecutionRuntimeFacts value = mock(TaskExecutionRuntimeFacts.class);
            TaskAgentRuntimeSession session = mock(TaskAgentRuntimeSession.class);
            when(session.canInvoke()).thenReturn(true);
            when(session.agentProfileId()).thenReturn(profileId);
            when(session.agentProfileVersion()).thenReturn(8L);
            when(session.agentScopeKey()).thenReturn(sessionKey);
            when(value.runtimeSession()).thenReturn(session);

            PolicySnapshot policy = mock(PolicySnapshot.class);
            when(policy.agentProfileId()).thenReturn(profileId);
            when(policy.agentProfileVersion()).thenReturn(8L);
            when(policy.budget()).thenReturn(new PolicyBudget(10_000, 5, 5, 30));
            when(value.policySnapshot()).thenReturn(policy);
            when(value.planVersion()).thenReturn(Optional.empty());
            when(value.stepExecution()).thenReturn(Optional.empty());

            TaskTokenGrantScope tokenScope = mock(TaskTokenGrantScope.class);
            TaskTokenExecutionContext authorization = mock(TaskTokenExecutionContext.class);
            when(authorization.scope()).thenReturn(tokenScope);
            when(value.authorization()).thenReturn(authorization);

            TaskExecution execution = mock(TaskExecution.class);
            when(execution.id()).thenReturn(executionId);
            when(execution.attempt()).thenReturn(1);
            when(execution.status()).thenReturn(TaskExecutionStatus.RUNNING);
            when(value.execution()).thenReturn(execution);

            AgentRunSegment segment = mock(AgentRunSegment.class);
            when(segment.kind()).thenReturn(AgentRunSegmentKind.INVOKE);
            when(segment.sequence()).thenReturn(1L);
            AgentRun run = mock(AgentRun.class);
            when(run.id()).thenReturn(runId);
            when(run.currentSegment()).thenReturn(segment);
            when(run.segments()).thenReturn(List.of());
            when(value.agentRun()).thenReturn(run);

            ExecutionLease lease = mock(ExecutionLease.class);
            when(lease.id()).thenReturn(ExecutionLeaseId.generate());
            when(lease.fencingToken()).thenReturn(FencingToken.initial());
            when(value.lease()).thenReturn(lease);
            return value;
        }

        private AgentScopeTaskRuntime runtime(TaskAgentStateSnapshotService snapshots) {
            return createRuntime(Optional.of(snapshots));
        }

        private AgentScopeTaskRuntime legacyRuntime() {
            return createRuntime(Optional.empty());
        }

        private AgentScopeTaskRuntime createRuntime(
                Optional<TaskAgentStateSnapshotService> snapshots) {
            ControlledTaskPlanParser parser = new ControlledTaskPlanParser();
            TaskAgentFactory factory = new TaskAgentFactory(
                    (id, version) -> new TaskAgentConfiguration(
                            id,
                            version,
                            "delayed",
                            Optional.empty(),
                            "Use controlled plans and Fixture Tools only.",
                            10,
                            1),
                    ignored -> new DelayedModel(),
                    stateStore,
                    new ControlledTaskToolkitFactory(parser),
                    runtimeRoot.resolve(UUID.randomUUID().toString()));
            if (snapshots.isPresent()) {
                return new AgentScopeTaskRuntime(
                        factory,
                        new AgentScopeTaskPlanningSnapshotMapper(),
                        new AgentScopeTaskPlanAdapter(parser),
                        (ignored, candidate) -> {
                            throw new AssertionError("plan publication is outside this test");
                        },
                        snapshots.orElseThrow(),
                        CLOCK);
            }
            return new AgentScopeTaskRuntime(
                    factory,
                    new AgentScopeTaskPlanningSnapshotMapper(),
                    new AgentScopeTaskPlanAdapter(parser),
                    (ignored, candidate) -> {
                        throw new AssertionError("plan publication is outside this test");
                    },
                    CLOCK);
        }

        private AgentState state(String text) {
            return AgentState.builder()
                    .userId(sessionKey.userId())
                    .sessionId(sessionKey.sessionId())
                    .addMessage(new UserMessage(text))
                    .build();
        }

        private void save(AgentState state) {
            stateStore.save(sessionKey.userId(), sessionKey.sessionId(), "agent_state", state);
        }

        private AgentState load() {
            return stateStore.get(
                            sessionKey.userId(), sessionKey.sessionId(), "agent_state", AgentState.class)
                    .orElseThrow();
        }
    }

    private static final class CapturingSnapshotService implements TaskAgentStateSnapshotService {

        private final AtomicReference<TaskAgentStateCheckpointCommand> checkpoint =
                new AtomicReference<>();
        private final AtomicReference<TaskAgentStateRecoveryCommand> recovery =
                new AtomicReference<>();
        private final AtomicReference<AgentState> recoveredState = new AtomicReference<>();

        @Override
        public TaskAgentStateCheckpointResult checkpoint(TaskAgentStateCheckpointCommand command) {
            checkpoint.set(command);
            return new TaskAgentStateCheckpointResult(
                    AgentStateSnapshotId.generate(),
                    RuntimeArtifactId.generate(),
                    1,
                    1,
                    command.safePoint());
        }

        @Override
        public TaskAgentStateRecoveryResult recover(TaskAgentStateRecoveryCommand command) {
            recovery.set(command);
            AgentState state = Optional.ofNullable(recoveredState.get())
                    .orElseThrow(() -> new AssertionError("recovered state was not configured"));
            return new TaskAgentStateRecoveryResult(
                    state.toJson(),
                    AgentStateSnapshotId.generate(),
                    1,
                    Optional.empty(),
                    List.of());
        }
    }

    private static final class DelayedModel implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                            .content(List.of(TextBlock.builder()
                                    .text("delayed terminal")
                                    .build()))
                            .usage(new ChatUsage(1, 1, 0.0))
                            .finishReason("stop")
                            .build())
                    .delayElements(Duration.ofSeconds(2));
        }

        @Override
        public String getModelName() {
            return "m3-i08-delayed-model";
        }
    }
}
