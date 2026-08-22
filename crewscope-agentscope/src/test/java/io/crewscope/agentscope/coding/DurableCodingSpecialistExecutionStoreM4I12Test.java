package io.crewscope.agentscope.coding;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.CodingCheckpointRepository;
import io.crewscope.application.coding.output.RepositoryAnalysisV1;
import io.crewscope.application.execution.DurableTaskExecutionEventService;
import io.crewscope.application.execution.TaskAgentStateSnapshotService;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.execution.TaskRuntimeEventCommitResult;
import io.crewscope.application.task.AgentStateSnapshotRepository;
import io.crewscope.application.task.StepExecutionRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.CodingCheckpointWorkState;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunSegment;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DurableCodingSpecialistExecutionStoreM4I12Test {

    @Test
    void persistsEveryModelAndToolCallAsOrderedContentFreeEvents() {
        DurableTaskExecutionEventService eventService = mock(
                DurableTaskExecutionEventService.class);
        when(eventService.commit(any())).thenReturn(mock(TaskRuntimeEventCommitResult.class));
        DurableCodingSpecialistExecutionStore store = store(eventService);
        TaskExecutionRuntimeFacts facts = facts();

        long next = store.recordTelemetry(
                facts,
                7,
                new CodingSpecialistTelemetry(
                        List.of(
                                new CodingSpecialistModelUsage(10, 4, 2, 14),
                                new CodingSpecialistModelUsage(20, 5, 8, 25)),
                        List.of("skill_load", "repository_read")),
                UUID.randomUUID());

        assertEquals(11, next);
        verify(eventService).commit(argThat(command ->
                command.event().sequence() == 7
                        && command.event().payload()
                                instanceof io.crewscope.application.execution
                                .TaskExecutionEventPayload.UsageReported usage
                        && usage.inputTokens() == 10
                        && usage.cachedTokens() == 2));
        verify(eventService).commit(argThat(command ->
                command.event().sequence() == 9
                        && command.event().payload()
                                instanceof io.crewscope.application.execution
                                .TaskExecutionEventPayload.ToolStarted tool
                        && tool.toolName().equals("skill_load")));
    }

    @Test
    void durableEventReceiptAlwaysPrecedesAgentStateSnapshotPublication() {
        DurableTaskExecutionEventService eventService = mock(
                DurableTaskExecutionEventService.class);
        TaskAgentStateSnapshotService snapshotService = mock(
                TaskAgentStateSnapshotService.class);
        CodingCheckpointRepository checkpointRepository = mock(
                CodingCheckpointRepository.class);
        AgentStateSnapshotRepository snapshotRepository = mock(
                AgentStateSnapshotRepository.class);
        StepExecutionRepository stepRepository = mock(StepExecutionRepository.class);
        when(eventService.commit(any())).thenReturn(mock(TaskRuntimeEventCommitResult.class));
        IllegalStateException unavailable = new IllegalStateException("snapshot unavailable");
        when(snapshotService.checkpoint(any())).thenThrow(unavailable);
        AtomicBoolean transactionActive = new AtomicBoolean();
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override
            public <T> T required(java.util.function.Supplier<T> operation) {
                boolean nested = transactionActive.getAndSet(true);
                try {
                    return operation.get();
                } finally {
                    transactionActive.set(nested);
                }
            }
        };
        DurableCodingSpecialistExecutionStore store =
                new DurableCodingSpecialistExecutionStore(
                        eventService,
                        snapshotService,
                        snapshotRepository,
                        checkpointRepository,
                        stepRepository,
                        transactions,
                        () -> {
                            assertTrue(
                                    transactionActive.get(),
                                    "authoritative time must be read inside REQUIRED transaction");
                            return UtcTimestamp.parse("2026-08-19T12:00:00Z");
                        });
        TaskExecutionRuntimeFacts facts = facts();
        CodingSpecialistCheckpointCommand command = new CodingSpecialistCheckpointCommand(
                facts,
                state(facts),
                authority(),
                CodingSpecialistCheckpointKind.PROGRESS,
                4,
                "Round complete",
                Optional.empty(),
                mock(Principal.class),
                UUID.randomUUID());

        assertThrows(IllegalStateException.class, () -> store.checkpoint(command));

        InOrder order = inOrder(eventService, snapshotService);
        order.verify(eventService).commit(any());
        order.verify(snapshotService).checkpoint(any());
        verify(checkpointRepository, never()).append(any());
    }

    private static DurableCodingSpecialistExecutionStore store(
            DurableTaskExecutionEventService eventService) {
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override
            public <T> T required(java.util.function.Supplier<T> operation) {
                return operation.get();
            }
        };
        return new DurableCodingSpecialistExecutionStore(
                eventService,
                mock(TaskAgentStateSnapshotService.class),
                mock(AgentStateSnapshotRepository.class),
                mock(CodingCheckpointRepository.class),
                mock(StepExecutionRepository.class),
                transactions,
                () -> UtcTimestamp.parse("2026-08-19T12:00:00Z"));
    }

    private static TaskExecutionRuntimeFacts facts() {
        TaskExecutionRuntimeFacts facts = mock(TaskExecutionRuntimeFacts.class);
        TaskExecution execution = mock(TaskExecution.class);
        TaskAgentRuntimeSession session = mock(TaskAgentRuntimeSession.class);
        AgentRun run = mock(AgentRun.class);
        AgentRunSegment segment = mock(AgentRunSegment.class);
        AgentProfileId profileId = AgentProfileId.from(
                "11111111-1111-4111-8111-111111111111");
        when(execution.id()).thenReturn(TaskExecutionId.generate());
        when(execution.attempt()).thenReturn(1);
        when(session.purpose()).thenReturn(TaskAgentSessionPurpose.SPECIALIST);
        when(session.agentProfileId()).thenReturn(profileId);
        when(session.agentProfileVersion()).thenReturn(1L);
        when(session.agentScopeKey()).thenReturn(new AgentScopeSessionKey(
                "crewscope:v1:user:store", "crewscope:v1:session:store"));
        when(run.id()).thenReturn(AgentRunId.generate());
        when(segment.sequence()).thenReturn(1L);
        when(run.currentSegment()).thenReturn(segment);
        when(facts.execution()).thenReturn(execution);
        when(facts.runtimeSession()).thenReturn(session);
        when(facts.agentRun()).thenReturn(run);
        when(facts.stepExecution()).thenReturn(Optional.of(mock(
                io.crewscope.domain.task.StepExecution.class)));
        return facts;
    }

    private static CodingSpecialistStateSnapshot state(TaskExecutionRuntimeFacts facts) {
        TaskAgentRuntimeSession session = facts.runtimeSession();
        String stableId = "crewscope-coding-" + session.agentProfileId() + "-v1";
        return new CodingSpecialistStateSnapshot(
                stableId,
                session.agentScopeKey().userId(),
                session.agentScopeKey().sessionId(),
                "{\"state\":\"safe\"}",
                new CodingCheckpointWorkState("# Plan\nVerify durability", List.of()));
    }

    private static CodingSpecialistAuthority authority() {
        return new CodingSpecialistAuthority(
                mock(CodingTargetSnapshot.class),
                mock(ExecutionWorkspace.class),
                mock(WorkspacePolicy.class),
                mock(RepositoryAnalysisV1.class),
                mock(DiffManifest.class),
                Optional.of(mock(TestEvidence.class)),
                Optional.of(mock(DiffArtifact.class)));
    }
}
