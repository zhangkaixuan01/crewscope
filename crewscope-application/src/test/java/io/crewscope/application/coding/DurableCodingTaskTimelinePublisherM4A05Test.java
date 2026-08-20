package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.task.TaskEventContext;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.DiffFileKind;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.event.WorkspaceDiffChanged;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** M4-A05 proof that live Diff transitions become canonical durable Task facts. */
class DurableCodingTaskTimelinePublisherM4A05Test {

    private final WorkItemScope scope = new WorkItemScope(
            OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(), WorkProjectId.generate());
    private final TaskId taskId = TaskId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private final ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
    private final UtcTimestamp now = UtcTimestamp.parse("2026-08-20T04:00:00Z");
    private final ExecutionWorkspaceRepository workspaces = mock(ExecutionWorkspaceRepository.class);
    private final DomainEventStore eventStore = mock(DomainEventStore.class);
    private final TaskEventRepository taskEvents = mock(TaskEventRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
    private DurableCodingTaskTimelinePublisher publisher;

    @BeforeEach
    void setUp() {
        TransactionExecutor transactions = mock(TransactionExecutor.class);
        when(transactions.required(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        publisher = new DurableCodingTaskTimelinePublisher(
                workspaces, eventStore, taskEvents, outbox, transactions);
        when(workspace.id()).thenReturn(workspaceId);
        when(workspace.scope()).thenReturn(scope);
        when(workspace.taskId()).thenReturn(taskId);
        when(workspace.taskExecutionId()).thenReturn(executionId);
        when(workspace.attempt()).thenReturn(1);
        when(workspace.status()).thenReturn(ExecutionWorkspaceStatus.ACTIVE);
        when(workspace.recoveryTargetStatus()).thenReturn(Optional.empty());
        when(workspace.recoveryGeneration()).thenReturn(0L);
        when(workspace.completionReason()).thenReturn(Optional.empty());
        when(workspace.failure()).thenReturn(Optional.empty());
        when(workspace.version()).thenReturn(3L);
        when(workspace.audit()).thenReturn(AuditMetadata.createdBy(PrincipalId.generate(), now));
        when(workspaces.findById(
                        scope.organizationId(), scope.teamId(), scope.projectId(), workspaceId))
                .thenReturn(Optional.of(workspace));
    }

    @Test
    void persistsResetWithStableIdentityAndTaskExecutionContext() {
        UUID eventId = UUID.randomUUID();
        DiffFileEntry file = DiffFileEntry.text(
                "src/App.java",
                Optional.empty(),
                DiffFileKind.MODIFIED,
                3,
                1,
                true,
                RuntimeContentHash.sha256("patch"),
                Optional.empty());
        WorkspaceDiffTimelineChange change = new WorkspaceDiffTimelineChange(
                eventId,
                scope,
                workspaceId,
                UUID.randomUUID(),
                1,
                DiffGeneration.first(),
                "RESET",
                List.of(file),
                List.of(),
                RuntimeContentHash.sha256("manifest"),
                now);

        publisher.workspaceDiffChanged(change);

        ArgumentCaptor<DomainEventEnvelope<?>> event = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(eventStore).append(event.capture());
        assertEquals(eventId, event.getValue().eventId());
        assertEquals("WORKSPACE_DIFF_RESET", event.getValue().eventType().value());
        WorkspaceDiffChanged payload = assertInstanceOf(
                WorkspaceDiffChanged.class, event.getValue().payload());
        assertEquals(List.of("src/App.java"), payload.upserts().stream()
                .map(WorkspaceDiffChanged.FileChange::path)
                .toList());
        verify(taskEvents).append(
                TaskEventContext.execution(taskId, executionId), event.getValue());
        verify(outbox).enqueue(any());
    }

    @Test
    void workspaceLifecycleUsesDeterministicDomainIdentityForItsCommittedVersion() {
        publisher.workspaceChanged(workspace);

        ArgumentCaptor<DomainEventEnvelope<?>> event = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(eventStore).append(event.capture());
        assertEquals("EXECUTION_WORKSPACE_CHANGED", event.getValue().eventType().value());
        assertEquals(3L, event.getValue().aggregateVersion());
        assertSame(scope.organizationId(), event.getValue().organizationId());
    }
}
