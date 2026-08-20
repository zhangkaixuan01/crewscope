package io.crewscope.application.coding;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.task.TaskEventContext;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.event.ExecutionWorkspaceChanged;
import io.crewscope.domain.coding.event.FinalDiffArtifactPublished;
import io.crewscope.domain.coding.event.TestEvidencePublished;
import io.crewscope.domain.coding.event.WorkspaceDiffChanged;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Atomically appends canonical Coding events, Task stream indexes and Outbox rows. */
public final class DurableCodingTaskTimelinePublisher implements CodingTaskTimelinePublisher {

    private static final String WORKSPACE_AGGREGATE = "EXECUTION_WORKSPACE";
    private static final String WORKSPACE_DIFF_AGGREGATE = "WORKSPACE_DIFF";
    private static final String TEST_AGGREGATE = "TEST_EVIDENCE";
    private static final String DIFF_AGGREGATE = "DIFF_ARTIFACT";

    private final ExecutionWorkspaceRepository workspaces;
    private final DomainEventStore eventStore;
    private final TaskEventRepository taskEvents;
    private final OutboxRepository outbox;
    private final TransactionExecutor transactions;

    public DurableCodingTaskTimelinePublisher(
            ExecutionWorkspaceRepository workspaces,
            DomainEventStore eventStore,
            TaskEventRepository taskEvents,
            OutboxRepository outbox,
            TransactionExecutor transactions) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.taskEvents = Objects.requireNonNull(taskEvents, "taskEvents");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public void workspaceChanged(ExecutionWorkspace workspace) {
        ExecutionWorkspace value = Objects.requireNonNull(workspace, "workspace");
        UUID eventId = stableId("workspace", value.id(), value.version());
        append(
                eventId,
                "EXECUTION_WORKSPACE_CHANGED",
                value,
                WORKSPACE_AGGREGATE,
                value.id().value(),
                value.version(),
                value.audit().updatedAt(),
                ExecutionWorkspaceChanged.from(value));
    }

    @Override
    public void workspaceDiffChanged(WorkspaceDiffTimelineChange change) {
        WorkspaceDiffTimelineChange value = Objects.requireNonNull(change, "change");
        transactions.required(() -> {
            ExecutionWorkspace workspace = workspaces.findById(
                            value.scope().organizationId(),
                            value.scope().teamId(),
                            value.scope().projectId(),
                            value.workspaceId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Diff timeline Workspace no longer exists"));
            WorkspaceDiffChanged payload = new WorkspaceDiffChanged(
                    workspace.id().value(),
                    workspace.taskExecutionId().value(),
                    workspace.attempt(),
                    value.streamEpoch(),
                    value.sequence(),
                    value.generation().value(),
                    value.changeKind(),
                    value.manifestHash().value(),
                    WorkspaceDiffChanged.files(value.upserts()),
                    value.removals().stream().map(path -> path.value()).toList());
            appendWithinTransaction(
                    value.eventId(),
                    "WORKSPACE_DIFF_" + value.changeKind(),
                    workspace,
                    WORKSPACE_DIFF_AGGREGATE,
                    workspace.id().value(),
                    value.generation().value(),
                    value.occurredAt(),
                    payload);
            return null;
        });
    }

    @Override
    public void testEvidencePublished(TestEvidence evidence) {
        TestEvidence value = Objects.requireNonNull(evidence, "evidence");
        append(
                stableId("test", value.id(), value.evidenceHash()),
                "TEST_EVIDENCE_PUBLISHED",
                value.scope(),
                value.taskId(),
                value.taskExecutionId(),
                TEST_AGGREGATE,
                value.id().value(),
                value.sequence().value(),
                value.audit().createdAt(),
                TestEvidencePublished.from(value));
    }

    @Override
    public void finalDiffArtifactPublished(DiffArtifact artifact) {
        DiffArtifact value = Objects.requireNonNull(artifact, "artifact");
        append(
                stableId("final-diff", value.id(), value.finalHash()),
                "FINAL_DIFF_ARTIFACT_PUBLISHED",
                value.scope(),
                value.taskId(),
                value.taskExecutionId(),
                DIFF_AGGREGATE,
                value.id().value(),
                value.manifest().generation().value(),
                value.audit().createdAt(),
                FinalDiffArtifactPublished.from(value));
    }

    private void append(
            UUID eventId,
            String eventType,
            ExecutionWorkspace workspace,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            UtcTimestamp occurredAt,
            DomainEvent payload) {
        transactions.required(() -> {
            appendWithinTransaction(
                    eventId,
                    eventType,
                    workspace,
                    aggregateType,
                    aggregateId,
                    aggregateVersion,
                    occurredAt,
                    payload);
            return null;
        });
    }

    private void append(
            UUID eventId,
            String eventType,
            io.crewscope.domain.workitem.WorkItemScope scope,
            io.crewscope.domain.task.TaskId taskId,
            io.crewscope.domain.task.TaskExecutionId executionId,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            UtcTimestamp occurredAt,
            DomainEvent payload) {
        transactions.required(() -> {
            DomainEventEnvelope<DomainEvent> event = envelope(
                    eventId,
                    eventType,
                    scope,
                    aggregateType,
                    aggregateId,
                    aggregateVersion,
                    occurredAt,
                    payload);
            persist(TaskEventContext.execution(taskId, executionId), event);
            return null;
        });
    }

    private void appendWithinTransaction(
            UUID eventId,
            String eventType,
            ExecutionWorkspace workspace,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            UtcTimestamp occurredAt,
            DomainEvent payload) {
        DomainEventEnvelope<DomainEvent> event = envelope(
                eventId,
                eventType,
                workspace.scope(),
                aggregateType,
                aggregateId,
                aggregateVersion,
                occurredAt,
                payload);
        persist(TaskEventContext.execution(
                workspace.taskId(), workspace.taskExecutionId()), event);
    }

    private DomainEventEnvelope<DomainEvent> envelope(
            UUID eventId,
            String eventType,
            io.crewscope.domain.workitem.WorkItemScope scope,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            UtcTimestamp occurredAt,
            DomainEvent payload) {
        return new DomainEventEnvelope<>(
                eventId,
                EventType.from(eventType),
                SchemaVersion.V1,
                scope.organizationId(),
                Optional.of(scope.teamId()),
                Optional.of(scope.workspaceId()),
                new AggregateReference(aggregateType, aggregateId),
                aggregateVersion,
                EventActor.anonymousService(),
                eventId,
                Optional.empty(),
                Optional.of("coding-timeline:" + eventId),
                occurredAt,
                payload);
    }

    private void persist(
            TaskEventContext context, DomainEventEnvelope<? extends DomainEvent> event) {
        eventStore.append(event);
        taskEvents.append(context, event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    }

    private static UUID stableId(String kind, Object identity, Object version) {
        String source = "crewscope:coding-timeline:" + kind + ':' + identity + ':' + version;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }
}
