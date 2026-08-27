package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.execution.RealtimeStreamEventIds;
import io.crewscope.application.task.TaskEvent;
import io.crewscope.application.task.TaskEventContext;
import io.crewscope.application.task.TaskEventCursor;
import io.crewscope.application.task.TaskEventCursorExpiredException;
import io.crewscope.application.task.TaskEventPage;
import io.crewscope.application.task.TaskEventQuery;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.application.task.TaskPublicEventMapper;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.event.StreamType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL Task Event index over canonical DomainEvent facts. */
@Repository
public class JdbcTaskEventRepository implements TaskEventRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskPublicEventMapper publicEventMapper = new TaskPublicEventMapper();

    public JdbcTaskEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(
            TaskEventContext context,
            DomainEventEnvelope<? extends DomainEvent> domainEvent) {
        TaskEventContext association = Objects.requireNonNull(context, "context");
        DomainEventEnvelope<? extends DomainEvent> source =
                Objects.requireNonNull(domainEvent, "domainEvent");
        TeamId teamId = source.teamId().orElseThrow();
        var workspaceId = source.workspaceId().orElseThrow();
        UUID streamEventId = RealtimeStreamEventIds.forDomain(StreamType.TASK, source.eventId());
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO crewscope.task_event (
                    event_id,
                    organization_id, team_id, workspace_id, project_id, task_id,
                    task_execution_id, step_execution_id, agent_run_id, execution_lease_id,
                    domain_event_id, occurred_at, created_at
                )
                SELECT ?, task.organization_id, task.team_id, task.workspace_id, task.project_id,
                       task.id, ?, ?, ?, ?, ?, ?, ?
                FROM crewscope.task task
                WHERE task.organization_id = ? AND task.team_id = ?
                  AND task.workspace_id = ? AND task.id = ?
                """,
                streamEventId,
                association.taskExecutionId().map(TaskExecutionId::value).orElse(null),
                association.stepExecutionId().map(StepExecutionId::value).orElse(null),
                association.agentRunId().map(AgentRunId::value).orElse(null),
                association.executionLeaseId().map(ExecutionLeaseId::value).orElse(null),
                source.eventId(),
                source.occurredAt().toOffsetDateTime(),
                source.occurredAt().toOffsetDateTime(),
                source.organizationId().value(),
                teamId.value(),
                workspaceId.value(),
                association.taskId().value());
        if (inserted != 1) {
            throw new IllegalArgumentException(
                    "Task Event association must match the DomainEvent tenant scope");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public TaskEventPage findPage(TaskEventQuery query, boolean taskTerminal) {
        TaskEventQuery required = Objects.requireNonNull(query, "query");
        required.cursor().ifPresent(cursor -> requireRetained(required, cursor));
        long afterPosition = required.cursor().map(TaskEventCursor::position).orElse(0L);
        java.util.ArrayList<Object> parameters = new java.util.ArrayList<>();
        parameters.add(required.scope().organizationId().value());
        parameters.add(required.scope().teamId().value());
        parameters.add(required.taskId().value());
        parameters.add(afterPosition);
        parameters.addAll(publicEventMapper.publicEventTypes());
        parameters.add(required.limit() + 1);
        List<TaskEvent> rows = jdbcTemplate.query(
                """
                SELECT stream.position, stream.event_id AS stream_event_id,
                       stream.organization_id, stream.team_id, stream.task_id,
                       stream.task_execution_id, stream.step_execution_id,
                       stream.agent_run_id, stream.execution_lease_id,
                       event.event_id AS domain_event_id,
                       event.event_type, event.schema_version,
                       event.subject_type, event.subject_id, event.aggregate_version,
                       event.correlation_id, event.causation_id,
                       event.occurred_at, event.payload::TEXT AS payload,
                       COALESCE(previous.maximum_version, -1) AS previous_version
                FROM crewscope.task_event stream
                JOIN crewscope.domain_event event
                  ON event.organization_id = stream.organization_id
                 AND event.event_id = stream.domain_event_id
                LEFT JOIN LATERAL (
                    SELECT MAX(previous_event.aggregate_version) AS maximum_version
                    FROM crewscope.task_event previous_stream
                    JOIN crewscope.domain_event previous_event
                      ON previous_event.organization_id = previous_stream.organization_id
                     AND previous_event.event_id = previous_stream.domain_event_id
                    WHERE previous_stream.organization_id = stream.organization_id
                      AND previous_stream.task_id = stream.task_id
                      AND previous_stream.position < stream.position
                      AND previous_event.subject_type = event.subject_type
                      AND previous_event.subject_id = event.subject_id
                ) previous ON TRUE
                WHERE stream.organization_id = ?
                  AND stream.team_id = ?
                  AND stream.task_id = ?
                  AND stream.position > ?
                  AND event.event_type IN (%s)
                ORDER BY stream.position ASC
                LIMIT ?
                """.formatted(publicEventPlaceholders()),
                this::mapEvent,
                parameters.toArray());
        boolean hasMore = rows.size() > required.limit();
        List<TaskEvent> page = hasMore
                ? List.copyOf(rows.subList(0, required.limit()))
                : List.copyOf(rows);
        return new TaskEventPage(page, hasMore, taskTerminal);
    }

    private String publicEventPlaceholders() {
        return String.join(", ", java.util.Collections.nCopies(
                publicEventMapper.publicEventTypes().size(), "?"));
    }

    private void requireRetained(TaskEventQuery query, TaskEventCursor cursor) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.task_event
                WHERE organization_id = ? AND team_id = ? AND task_id = ?
                  AND position = ? AND event_id = ?
                """,
                Integer.class,
                query.scope().organizationId().value(),
                query.scope().teamId().value(),
                query.taskId().value(),
                cursor.position(),
                cursor.eventId());
        if (count == null || count != 1) {
            throw new TaskEventCursorExpiredException();
        }
    }

    private TaskEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        UUID streamEventId = resultSet.getObject("stream_event_id", UUID.class);
        UUID domainEventId = resultSet.getObject("domain_event_id", UUID.class);
        OrganizationId organizationId = new OrganizationId(
                resultSet.getObject("organization_id", UUID.class));
        TeamId teamId = new TeamId(resultSet.getObject("team_id", UUID.class));
        TaskId taskId = new TaskId(resultSet.getObject("task_id", UUID.class));
        long aggregateVersion = resultSet.getLong("aggregate_version");
        long previousVersion = resultSet.getLong("previous_version");
        String eventType = resultSet.getString("event_type");
        UtcTimestamp occurredAt = UtcTimestamp.from(
                resultSet.getObject("occurred_at", OffsetDateTime.class));
        Map<String, Object> publicPayload = publicEventMapper.map(
                eventType, readPayload(resultSet.getString("payload")));
        RealtimeEventEnvelope<Map<String, Object>> envelope = new RealtimeEventEnvelope<>(
                streamEventId,
                Optional.of(domainEventId),
                StreamType.TASK,
                EventType.from(eventType),
                SchemaVersion.from(resultSet.getString("schema_version")),
                Optional.of(new AggregateReference(
                        resultSet.getString("subject_type"),
                        resultSet.getObject("subject_id", UUID.class))),
                Optional.of(aggregateVersion),
                resultSet.getObject("correlation_id", UUID.class),
                Optional.ofNullable(resultSet.getObject("causation_id", UUID.class)),
                occurredAt,
                publicPayload);
        TaskEventCursor cursor = new TaskEventCursor(
                organizationId,
                teamId,
                taskId,
                resultSet.getLong("position"),
                streamEventId);
        TaskEventContext context = new TaskEventContext(
                taskId,
                optionalId(resultSet, "task_execution_id", TaskExecutionId::new),
                optionalId(resultSet, "step_execution_id", StepExecutionId::new),
                optionalId(resultSet, "agent_run_id", AgentRunId::new),
                optionalId(resultSet, "execution_lease_id", ExecutionLeaseId::new));
        return new TaskEvent(cursor, context, aggregateVersion > previousVersion + 1, envelope);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String value) {
        return new LinkedHashMap<>(objectMapper.readValue(value, Map.class));
    }

    private static <T> Optional<T> optionalId(
            ResultSet resultSet,
            String column,
            java.util.function.Function<UUID, T> factory) throws SQLException {
        return Optional.ofNullable(resultSet.getObject(column, UUID.class)).map(factory);
    }
}
