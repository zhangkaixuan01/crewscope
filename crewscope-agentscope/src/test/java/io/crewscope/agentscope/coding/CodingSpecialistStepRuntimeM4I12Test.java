package io.crewscope.agentscope.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.Toolkit;
import io.crewscope.application.coding.output.CodeChangeResultV1;
import io.crewscope.application.coding.output.CodingOutputValidationException;
import io.crewscope.application.coding.output.CodingOutputValidator;
import io.crewscope.application.execution.ExecutionInterruptToken;
import io.crewscope.application.execution.TaskAgentStateRecoveryResult;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.domain.coding.CodingCheckpoint;
import io.crewscope.domain.coding.CodingCheckpointId;
import io.crewscope.domain.coding.CodingCheckpointWorkState;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.task.AgentStateSnapshotId;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.StepExecutionStatus;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class CodingSpecialistStepRuntimeM4I12Test {

    private static final AgentProfileId PROFILE_ID = AgentProfileId.from(
            "11111111-1111-4111-8111-111111111111");

    private AgentScopeCodingRuntime agentRuntime;
    private CodingSpecialistAuthorityGateway authorityGateway;
    private CodingSpecialistExecutionStore executionStore;
    private CodingOutputValidator outputValidator;
    private Principal executor;

    @BeforeEach
    void setUp() {
        agentRuntime = mock(AgentScopeCodingRuntime.class);
        authorityGateway = mock(CodingSpecialistAuthorityGateway.class);
        executionStore = mock(CodingSpecialistExecutionStore.class);
        outputValidator = mock(CodingOutputValidator.class);
        executor = mock(Principal.class);
    }

    @Test
    void succeedsOnlyAfterCheckpointAndAuthorityRevalidation() {
        Fixture fixture = fixture(0, true, "attempt-one");

        CodingSpecialistStepResult result = runtime().execute(fixture.request(executor, false))
                .block(Duration.ofSeconds(2));

        assertEquals(CodingSpecialistStepStatus.SUCCEEDED, result.status());
        assertEquals(1, result.modelCalls());
        assertEquals(0, result.repairRounds());
        InOrder order = inOrder(executionStore, outputValidator);
        order.verify(executionStore).checkpoint(any());
        order.verify(outputValidator).validateCodeChangeResult(
                eq(fixture.output),
                eq(fixture.authority.repositoryAnalysis()),
                eq(fixture.authority.target()),
                eq(fixture.authority.workspace()),
                eq(fixture.authority.finalDiffArtifact().orElseThrow()),
                eq(fixture.authority.testEvidence().orElseThrow()));
        order.verify(executionStore).succeed(
                eq(fixture.facts), eq(2L), eq(executor), any());
    }

    @Test
    void failedTestsUseSameRunAndSessionForBoundedRepair() {
        Fixture fixture = fixture(2, false, "repair");
        TestEvidence failed = fixture.authority.testEvidence().orElseThrow();
        CodingSpecialistAuthority successful = authority(2, true);
        Queue<CodingSpecialistAuthority> rounds = new ArrayDeque<>(
                List.of(fixture.authority, successful));
        when(authorityGateway.inspect(eq(fixture.facts), any(Integer.class)))
                .thenAnswer(ignored -> rounds.remove());
        when(agentRuntime.execute(any()))
                .thenReturn(Mono.just(fixture.runResult), Mono.just(fixture.runResult));

        CodingSpecialistStepResult result = runtime().execute(fixture.request(executor, false))
                .block(Duration.ofSeconds(2));

        assertEquals(CodingSpecialistStepStatus.SUCCEEDED, result.status());
        assertEquals(2, result.modelCalls());
        assertEquals(1, result.repairRounds());
        verify(authorityGateway).openRound(fixture.facts, 2, Optional.of(failed));
        verify(agentRuntime, org.mockito.Mockito.times(2)).execute(any());
        verify(executionStore, org.mockito.Mockito.times(2)).checkpoint(any());
    }

    @Test
    void exhaustedRepairBudgetFailsWithoutAnotherModelCall() {
        Fixture fixture = fixture(0, false, "budget");

        CodingSpecialistStepResult result = runtime().execute(fixture.request(executor, false))
                .block(Duration.ofSeconds(2));

        assertEquals(CodingSpecialistStepStatus.FAILED, result.status());
        assertEquals(Optional.of("TEST_REPAIR_BUDGET_EXHAUSTED"), result.failureCode());
        verify(agentRuntime).execute(any());
        verify(executionStore).fail(
                eq(fixture.facts),
                eq(2L),
                eq("TEST_REPAIR_BUDGET_EXHAUSTED"),
                eq(false),
                eq(executor),
                any());
    }

    @Test
    void forgedStructuredResultFailsClosedAfterCheckpoint() {
        Fixture fixture = fixture(0, true, "forged");
        org.mockito.Mockito.doThrow(new CodingOutputValidationException(
                        "codeChangeResult.diffArtifact", "does not match authority"))
                .when(outputValidator)
                .validateCodeChangeResult(any(), any(), any(), any(), any(), any());

        CodingSpecialistStepResult result = runtime().execute(fixture.request(executor, false))
                .block(Duration.ofSeconds(2));

        assertEquals(CodingSpecialistStepStatus.FAILED, result.status());
        assertEquals(Optional.of("CODING_RESULT_INVALID"), result.failureCode());
        verify(executionStore).checkpoint(any());
        verify(executionStore).fail(
                eq(fixture.facts), eq(2L), eq("CODING_RESULT_INVALID"), eq(false),
                eq(executor), any());
        verify(executionStore, never()).succeed(any(), any(Long.class), any(), any());
    }

    @Test
    void recoveryReconcilesWorkspaceThenRestoresSnapshotBeforeSameRunResume() {
        Fixture fixture = fixture(0, true, "resume");
        TaskAgentStateRecoveryResult recovered = new TaskAgentStateRecoveryResult(
                "{\"session_id\":\"resume\"}",
                AgentStateSnapshotId.generate(),
                3,
                Optional.empty(),
                List.of());
        when(executionStore.recoverState(fixture.facts, 8)).thenReturn(recovered);

        CodingSpecialistStepResult result = runtime().execute(fixture.request(executor, true))
                .block(Duration.ofSeconds(2));

        assertEquals(CodingSpecialistStepStatus.SUCCEEDED, result.status());
        InOrder order = inOrder(authorityGateway, executionStore, agentRuntime);
        order.verify(executionStore).beginStep(fixture.facts, executor);
        order.verify(authorityGateway).recover(fixture.facts);
        order.verify(executionStore).recoverState(fixture.facts, 8);
        order.verify(agentRuntime).restore(
                fixture.facts.runtimeSession(), recovered.agentStateJson());
        order.verify(authorityGateway).openRound(fixture.facts, 1, Optional.empty());
        order.verify(agentRuntime).execute(any());
    }

    @Test
    void pauseInterruptsExactSessionAndPersistsPausedCheckpoint() {
        Fixture fixture = fixture(1, true, "pause");
        Sinks.One<CodingSpecialistRunResult> call = Sinks.one();
        when(agentRuntime.execute(any())).thenReturn(call.asMono());
        when(agentRuntime.interrupt(fixture.facts.runtimeSession())).thenReturn(true);
        when(agentRuntime.snapshot(any())).thenReturn(fixture.state);

        CompletableFuture<CodingSpecialistStepResult> result = runtime()
                .execute(fixture.request(executor, false))
                .toFuture();
        CodingSpecialistControlResult control = runtimeReference.control(
                fixture.facts,
                CodingSpecialistControlAction.PAUSE,
                Optional.of(new ExecutionInterruptToken("pause-token")),
                "Paused by member");
        call.tryEmitError(new IllegalStateException("interrupted"));
        CodingSpecialistStepResult completed = result.join();

        assertTrue(control.accepted());
        assertTrue(control.interruptDelivered());
        assertEquals(CodingSpecialistStepStatus.PAUSED, completed.status());
        verifyCheckpointKind(CodingSpecialistCheckpointKind.PAUSED);
    }

    @Test
    void cancelInterruptsAndCannotCommitSuccess() {
        Fixture fixture = fixture(1, true, "cancel");
        Sinks.One<CodingSpecialistRunResult> call = Sinks.one();
        when(agentRuntime.execute(any())).thenReturn(call.asMono());
        when(agentRuntime.interrupt(fixture.facts.runtimeSession())).thenReturn(true);
        when(agentRuntime.snapshot(any())).thenReturn(fixture.state);

        CompletableFuture<CodingSpecialistStepResult> result = runtime()
                .execute(fixture.request(executor, false))
                .toFuture();
        CodingSpecialistControlResult control = runtimeReference.control(
                fixture.facts,
                CodingSpecialistControlAction.CANCEL,
                Optional.empty(),
                "Cancelled by member");
        call.tryEmitError(new IllegalStateException("interrupted"));
        CodingSpecialistStepResult completed = result.join();

        assertTrue(control.accepted());
        assertEquals(CodingSpecialistStepStatus.CANCELLED, completed.status());
        verifyCheckpointKind(CodingSpecialistCheckpointKind.CANCELLED);
        verify(executionStore, never()).succeed(any(), any(Long.class), any(), any());
    }

    @Test
    void successorAttemptUsesNewSessionAndNeverRestoresPriorAttemptState() {
        Fixture first = fixture(0, true, "attempt-a");

        assertEquals(CodingSpecialistStepStatus.SUCCEEDED,
                runtime().execute(first.request(executor, false)).block(Duration.ofSeconds(2)).status());
        Fixture successor = fixture(0, true, "attempt-b");
        assertEquals(CodingSpecialistStepStatus.SUCCEEDED,
                runtimeReference.execute(successor.request(executor, false))
                        .block(Duration.ofSeconds(2)).status());

        assertFalse(first.facts.execution().id().equals(successor.facts.execution().id()));
        assertFalse(first.facts.runtimeSession().agentScopeKey()
                .equals(successor.facts.runtimeSession().agentScopeKey()));
        verify(agentRuntime, never()).restore(eq(successor.facts.runtimeSession()), any());
    }

    private CodingSpecialistStepRuntime runtimeReference;

    private CodingSpecialistStepRuntime runtime() {
        runtimeReference = new CodingSpecialistStepRuntime(
                agentRuntime, authorityGateway, executionStore, outputValidator);
        return runtimeReference;
    }

    private Fixture fixture(int repairBudget, boolean testsSucceeded, String suffix) {
        TaskExecutionRuntimeFacts facts = facts(suffix);
        CodingSpecialistAuthority authority = authority(repairBudget, testsSucceeded);
        CodingSpecialistRunResult result = runResult(suffix);
        when(authorityGateway.openRound(eq(facts), any(Integer.class), any()))
                .thenAnswer(invocation -> new CodingSpecialistRound(
                        invocation.getArgument(1), new Toolkit(), "Execute round"));
        when(authorityGateway.inspect(eq(facts), any(Integer.class))).thenReturn(authority);
        when(agentRuntime.execute(any())).thenReturn(Mono.just(result));
        org.mockito.Mockito.doAnswer(invocation -> {
            CodingSpecialistCheckpointCommand command = invocation.getArgument(0);
            CodingCheckpoint checkpoint = mock(CodingCheckpoint.class);
            CodingCheckpointId id = CodingCheckpointId.generate();
            when(checkpoint.id()).thenReturn(id);
            return new CodingSpecialistCheckpointReceipt(
                    checkpoint, AgentStateSnapshotId.generate(), command.eventSequence());
        }).when(executionStore).checkpoint(any());
        return new Fixture(facts, authority, result, result.output(), result.stateSnapshot());
    }

    private TaskExecutionRuntimeFacts facts(String suffix) {
        TaskExecutionRuntimeFacts facts = mock(TaskExecutionRuntimeFacts.class);
        TaskExecution execution = mock(TaskExecution.class);
        TaskExecutionId executionId = TaskExecutionId.generate();
        StepExecution step = mock(StepExecution.class);
        TaskAgentRuntimeSession session = mock(TaskAgentRuntimeSession.class);
        when(execution.id()).thenReturn(executionId);
        when(step.id()).thenReturn(StepExecutionId.generate());
        when(step.status()).thenReturn(StepExecutionStatus.READY);
        when(session.purpose()).thenReturn(TaskAgentSessionPurpose.SPECIALIST);
        when(session.agentProfileId()).thenReturn(PROFILE_ID);
        when(session.agentProfileVersion()).thenReturn(1L);
        when(session.agentScopeKey()).thenReturn(new AgentScopeSessionKey(
                "crewscope:v1:user:" + suffix, "crewscope:v1:session:" + suffix));
        when(facts.execution()).thenReturn(execution);
        when(facts.stepExecution()).thenReturn(Optional.of(step));
        when(facts.runtimeSession()).thenReturn(session);
        return facts;
    }

    private CodingSpecialistAuthority authority(int repairBudget, boolean succeeded) {
        CodingTargetSnapshot target = mock(CodingTargetSnapshot.class);
        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        WorkspacePolicy policy = mock(WorkspacePolicy.class);
        TestEvidence evidence = mock(TestEvidence.class);
        when(policy.operationBudget()).thenReturn(new WorkspaceOperationBudget(
                10, 10, 10_000, 10, 100_000, 100_000, repairBudget));
        when(evidence.succeeded()).thenReturn(succeeded);
        return new CodingSpecialistAuthority(
                target,
                workspace,
                policy,
                mock(io.crewscope.application.coding.output.RepositoryAnalysisV1.class),
                mock(DiffManifest.class),
                Optional.of(evidence),
                Optional.of(mock(DiffArtifact.class)));
    }

    private CodingSpecialistRunResult runResult(String suffix) {
        CodeChangeResultV1 output = mock(CodeChangeResultV1.class);
        CodingSpecialistStateSnapshot state = new CodingSpecialistStateSnapshot(
                "crewscope-coding-" + PROFILE_ID + "-v1",
                "crewscope:v1:user:" + suffix,
                "crewscope:v1:session:" + suffix,
                "{\"state\":\"" + suffix + "\"}",
                new CodingCheckpointWorkState("# Plan\nDo the work", List.of()));
        return new CodingSpecialistRunResult(output, state);
    }

    private void verifyCheckpointKind(CodingSpecialistCheckpointKind kind) {
        verify(executionStore).checkpoint(org.mockito.ArgumentMatchers.argThat(
                command -> command.kind() == kind));
    }

    private record Fixture(
            TaskExecutionRuntimeFacts facts,
            CodingSpecialistAuthority authority,
            CodingSpecialistRunResult runResult,
            CodeChangeResultV1 output,
            CodingSpecialistStateSnapshot state) {

        private CodingSpecialistStepRequest request(Principal executor, boolean recover) {
            return new CodingSpecialistStepRequest(
                    facts, executor, 1, UUID.randomUUID(), recover, 8);
        }
    }
}
