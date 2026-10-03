package io.crewscope.application.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.task.TaskEvent;
import io.crewscope.application.task.TaskEventContext;
import io.crewscope.application.task.TaskEventCursor;
import io.crewscope.application.task.TaskEventPage;
import io.crewscope.application.task.TaskEventQuery;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.event.StreamType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPriority;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionTerminal;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.task.TaskResponsibilitySnapshotEntry;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.task.TaskSourceType;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * Source assembly contract (D7): only whitelisted sanitized kinds of the requested attempt
 * become model input, the whole stream is paged through, and oversize sources are rejected
 * explicitly instead of being silently truncated.
 */
final class KnowledgeDistillationSourceAssemblerTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-02T09:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, WorkspaceId.generate(), WorkProjectId.generate());
    private final TaskId taskId = TaskId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private final TaskExecution execution = execution();
    private final Task task = task();

    @Test
    void rendersOnlyWhitelistedKindsOfTheRequestedAttempt() {
        TaskExecutionId otherAttempt = TaskExecutionId.generate();
        FakeTaskEvents stream = new FakeTaskEvents(List.of(
                runEvent(1, execution.id(), "TEXT_DELTA", "Drained the pool. "),
                runEvent(2, execution.id(), "PROGRESS", "45"),
                runEvent(3, execution.id(), "TEXT_DELTA", "Rolled the schema."),
                runEvent(4, execution.id(), "STRUCTURED_OUTPUT", "{\"ok\":true}"),
                runEvent(5, execution.id(), "THINKING_SUMMARY", "x".repeat(2_500)),
                runEvent(6, execution.id(), "COMPLETED", null),
                runEvent(7, execution.id(), "UNKNOWN_KIND", "noise"),
                runEvent(8, otherAttempt, "TEXT_DELTA", "stale attempt"),
                taskLevelEvent(9)));
        String source = new KnowledgeDistillationSourceAssembler(stream).assemble(task, execution);

        assertTrue(source.contains("Drained the pool. Rolled the schema."));
        assertTrue(source.contains("\n[structured output]\n{\"ok\":true}\n"));
        assertTrue(source.contains("[reasoning] " + "x".repeat(2_000)));
        assertTrue(source.endsWith("\n[completed]\n"));
        assertEquals(-1, source.indexOf("stale attempt"));
        assertEquals(-1, source.indexOf("45"));
        assertEquals(-1, source.indexOf("noise"));
        assertEquals(-1, source.indexOf("x".repeat(2_001)));
    }

    @Test
    void pagesThroughTheEntireStreamWithKeysetCursors() {
        // One full 100-row page plus a remainder forces exactly two keyset reads.
        List<TaskEvent> deltas = LongStream.rangeClosed(1, 149)
                .mapToObj(position -> runEvent(
                        position, execution.id(), "TEXT_DELTA", "chunk-" + position + " "))
                .toList();
        List<TaskEvent> completion = List.of(runEvent(150, execution.id(), "COMPLETED", null));
        FakeTaskEvents stream = new FakeTaskEvents(deltas, completion);

        String source = new KnowledgeDistillationSourceAssembler(stream).assemble(task, execution);

        assertTrue(source.contains("chunk-1 chunk-2"));
        assertTrue(source.contains("chunk-149"));
        assertTrue(source.endsWith("\n[completed]\n"));
        assertEquals(2, stream.queries.size());
        assertTrue(stream.queries.get(0).cursor().isEmpty());
        assertEquals(100, stream.queries.get(0).limit());
        assertEquals(
                100,
                stream.queries.get(1).cursor().orElseThrow().position(),
                "the second page must keyset off the first page's last event");
    }

    @Test
    void rejectsOversizedSourceTextInsteadOfTruncatingIt() {
        String oversized = "a".repeat(KnowledgeDistillationSourceAssembler.MAX_SOURCE_TEXT_LENGTH + 1);
        FakeTaskEvents stream = new FakeTaskEvents(List.of(
                runEvent(1, execution.id(), "TEXT_DELTA", oversized)));

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> new KnowledgeDistillationSourceAssembler(stream).assemble(task, execution));

        assertEquals("distillation.sourceText", failure.error().details().get("field"));
    }

    // ------------------------------------------------------------------ fixtures

    private Task task() {
        PrincipalId ownerId = PrincipalId.generate();
        WorkItemId workItemId = WorkItemId.generate();
        TaskResponsibilitySnapshot responsibilities = new TaskResponsibilitySnapshot(
                scope,
                workItemId,
                List.of(
                        responsibility(ResponsibilityRole.OWNER, ownerId, PrincipalType.USER),
                        responsibility(
                                ResponsibilityRole.EXECUTOR,
                                PrincipalId.generate(),
                                PrincipalType.SPECIALIST_AGENT)),
                NOW);
        return Task.reconstitute(
                taskId,
                scope,
                workItemId,
                new TaskSource(
                        TaskSourceType.WORK_ITEM, scope, workItemId, 0,
                        Optional.empty(), Optional.empty()),
                new TaskBrief("Ship the release", List.of("Green build")),
                responsibilities,
                TaskStatus.ACTIVE,
                Optional.of(executionId),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(ownerId, NOW));
    }

    private TaskExecution execution() {
        return TaskExecution.reconstitute(
                executionId,
                scope,
                taskId,
                1,
                3,
                Optional.empty(),
                TaskExecutionPriority.NORMAL,
                NOW,
                TaskExecutionStatus.COMPLETED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(new TaskExecutionTerminal(
                        TaskExecutionStatus.COMPLETED, PrincipalId.generate(), NOW,
                        Optional.empty())),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(PrincipalId.generate(), NOW));
    }

    private static TaskResponsibilitySnapshotEntry responsibility(
            ResponsibilityRole role, PrincipalId principalId, PrincipalType type) {
        return new TaskResponsibilitySnapshotEntry(
                ResponsibilityAssignmentId.generate(),
                0,
                role,
                principalId,
                type,
                type == PrincipalType.USER
                        ? Optional.of(io.crewscope.domain.team.TeamMemberId.generate())
                        : Optional.empty(),
                NOW,
                NOW);
    }

    private TaskEvent runEvent(long position, TaskExecutionId executionId, String kind, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventKind", kind);
        if (text != null) {
            payload.put("safeText", text);
        }
        return event(position, executionId, "AGENT_RUN_EVENT_RECORDED", payload);
    }

    /** A task-level fact (no execution association) that must never reach the model. */
    private TaskEvent taskLevelEvent(long position) {
        return event(position, null, "TASK_DELEGATED_TO_AGENT", Map.of("safeText", "task fact"));
    }

    private TaskEvent event(
            long position, TaskExecutionId executionId, String type, Map<String, Object> payload) {
        UUID eventId = UUID.randomUUID();
        TaskEventCursor cursor = new TaskEventCursor(organizationId, teamId, taskId, position, eventId);
        RealtimeEventEnvelope<Map<String, Object>> envelope = new RealtimeEventEnvelope<>(
                eventId,
                Optional.of(UUID.randomUUID()),
                StreamType.TASK,
                EventType.from(type),
                SchemaVersion.V1,
                Optional.empty(),
                Optional.empty(),
                UUID.randomUUID(),
                Optional.empty(),
                NOW,
                payload);
        TaskEventContext context = executionId == null
                ? TaskEventContext.task(taskId)
                : TaskEventContext.agentRun(
                        taskId, executionId, Optional.empty(), AgentRunId.generate());
        return new TaskEvent(cursor, context, false, envelope);
    }

    /** Keyset-paged in-memory stream: each query resumes after the supplied cursor. */
    private static final class FakeTaskEvents implements TaskEventRepository {
        private final List<TaskEvent> events = new ArrayList<>();
        final List<TaskEventQuery> queries = new ArrayList<>();

        @SafeVarargs
        FakeTaskEvents(List<TaskEvent>... batches) {
            for (List<TaskEvent> batch : batches) {
                events.addAll(batch);
            }
        }

        @Override
        public void append(
                TaskEventContext context, DomainEventEnvelope<? extends DomainEvent> domainEvent) {
            throw new UnsupportedOperationException("read-only fixture");
        }

        @Override
        public TaskEventPage findPage(TaskEventQuery query, boolean taskTerminal) {
            queries.add(query);
            long after = query.cursor().map(value -> value.position()).orElse(0L);
            List<TaskEvent> remaining = events.stream()
                    .filter(event -> event.cursor().position() > after)
                    .toList();
            List<TaskEvent> page = remaining.stream().limit(query.limit()).toList();
            boolean hasMore = remaining.size() > page.size();
            return new TaskEventPage(page, hasMore, false);
        }
    }
}
