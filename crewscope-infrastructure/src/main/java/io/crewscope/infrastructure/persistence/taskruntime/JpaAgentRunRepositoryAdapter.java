package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunSegment;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.AgentRunSegmentStatus;
import io.crewscope.domain.task.AgentInterruptId;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.TaskExecutionId;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA parent plus append/update-only AgentRun Segment persistence. */
@Repository
public class JpaAgentRunRepositoryAdapter implements AgentRunRepository {

    private final TaskRuntimeJpaSupport support;
    private final NamedParameterJdbcTemplate jdbc;

    public JpaAgentRunRepositoryAdapter(
            TaskRuntimeJpaSupport support, NamedParameterJdbcTemplate jdbc) {
        this.support = Objects.requireNonNull(support, "support");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public AgentRun createNext(AgentRun run) {
        AgentRun value = Objects.requireNonNull(run, "run");
        AgentRunEntity row = support.mapper.toEntity(value);
        support.entityManager.persist(row);
        support.entityManager.flush();
        synchronizeSegments(value);
        return toDomain(row);
    }

    @Override
    @Transactional
    public AgentRun update(AgentRun run) {
        AgentRun value = Objects.requireNonNull(run, "run");
        long expected = TaskRuntimeJpaSupport.expected(value.version(), "agentRun.version");
        AgentRunEntity row = support.findScoped(
                        AgentRunEntity.class, value.scope().organizationId(), value.id().value())
                .orElseThrow(() -> new AggregateNotFoundException("AgentRun", value.id()));
        TaskRuntimeJpaSupport.requireVersion("AgentRun", value.id(), expected, row.version);
        support.mapper.copyState(row, value);
        synchronizeSegments(value);
        support.entityManager.flush();
        return toDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRun> findById(OrganizationId organizationId, AgentRunId agentRunId) {
        return support.findScoped(AgentRunEntity.class, organizationId, agentRunId.value())
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRun> findActiveBySession(
            OrganizationId organizationId, AgentRuntimeSessionId sessionId) {
        return support.entityManager.createQuery(
                        """
                        SELECT row FROM AgentRunEntity row
                        WHERE row.organizationId = :organizationId
                          AND row.runtimeSessionId = :sessionId
                          AND row.status IN ('RUNNING', 'INTERRUPTED')
                        """,
                        AgentRunEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("sessionId", sessionId.value())
                .getResultStream().findFirst().map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentRun> findByExecution(
            OrganizationId organizationId, TaskExecutionId executionId) {
        return findList(organizationId, "row.taskExecutionId", executionId.value());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentRun> findByStep(
            OrganizationId organizationId, StepExecutionId stepExecutionId) {
        return findList(organizationId, "row.stepExecutionId", stepExecutionId.value());
    }

    private List<AgentRun> findList(
            OrganizationId organizationId, String field, java.util.UUID value) {
        List<AgentRunEntity> rows = support.entityManager.createQuery(
                        "SELECT row FROM AgentRunEntity row WHERE row.organizationId = :organizationId"
                                + " AND " + field + " = :value ORDER BY row.runSequence, row.id",
                        AgentRunEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("value", value)
                .getResultList();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<java.util.UUID, List<AgentRunSegment>> segments = segmentsByRunIds(
                rows.stream().map(row -> row.id).toList());
        return rows.stream()
                .map(row -> support.mapper.toDomain(
                        row, segments.getOrDefault(row.id, List.of())))
                .toList();
    }

    private Map<java.util.UUID, List<AgentRunSegment>> segmentsByRunIds(
            List<java.util.UUID> runIds) {
        List<SegmentRow> values = jdbc.query(
                """
                SELECT agent_run_id, sequence, kind, resumed_from_interrupt_id,
                       status, started_at, ended_at
                FROM crewscope.agent_run_segment
                WHERE agent_run_id IN (:runIds)
                ORDER BY agent_run_id, sequence
                """,
                Map.of("runIds", runIds),
                (result, index) -> new SegmentRow(
                        result.getObject("agent_run_id", java.util.UUID.class),
                        new AgentRunSegment(
                                result.getLong("sequence"),
                                AgentRunSegmentKind.valueOf(result.getString("kind")),
                                Optional.ofNullable(result.getObject(
                                                "resumed_from_interrupt_id", java.util.UUID.class))
                                        .map(AgentInterruptId::new),
                                AgentRunSegmentStatus.valueOf(result.getString("status")),
                                io.crewscope.domain.shared.time.UtcTimestamp.from(
                                        result.getObject("started_at", OffsetDateTime.class)),
                                Optional.ofNullable(result.getObject("ended_at", OffsetDateTime.class))
                                        .map(io.crewscope.domain.shared.time.UtcTimestamp::from))));
        Map<java.util.UUID, List<AgentRunSegment>> grouped = new HashMap<>();
        values.forEach(value -> grouped
                .computeIfAbsent(value.runId(), ignored -> new ArrayList<>())
                .add(value.segment()));
        return grouped;
    }

    private void synchronizeSegments(AgentRun run) {
        for (AgentRunSegment segment : run.segments()) {
            HashMap<String, Object> parameters = new HashMap<>();
            parameters.put("runId", run.id().value());
            parameters.put("sequence", segment.sequence());
            parameters.put("kind", segment.kind().name());
            parameters.put("interruptId", segment.resumedFromInterruptId()
                    .map(AgentInterruptId::value).orElse(null));
            parameters.put("status", segment.status().name());
            parameters.put("startedAt", segment.startedAt().toOffsetDateTime());
            parameters.put("endedAt", segment.endedAt()
                    .map(io.crewscope.domain.shared.time.UtcTimestamp::toOffsetDateTime)
                    .orElse(null));
            jdbc.update(
                    """
                    INSERT INTO crewscope.agent_run_segment (
                        agent_run_id, sequence, kind, resumed_from_interrupt_id,
                        status, started_at, ended_at
                    ) VALUES (
                        :runId, :sequence, :kind, :interruptId,
                        :status, :startedAt, :endedAt
                    ) ON CONFLICT (agent_run_id, sequence) DO UPDATE
                    SET status = EXCLUDED.status, ended_at = EXCLUDED.ended_at
                    WHERE crewscope.agent_run_segment.kind = EXCLUDED.kind
                      AND crewscope.agent_run_segment.resumed_from_interrupt_id
                          IS NOT DISTINCT FROM EXCLUDED.resumed_from_interrupt_id
                      AND crewscope.agent_run_segment.started_at = EXCLUDED.started_at
                    """,
                    parameters);
        }
    }

    private AgentRun toDomain(AgentRunEntity row) {
        List<AgentRunSegment> segments = jdbc.query(
                """
                SELECT sequence, kind, resumed_from_interrupt_id, status, started_at, ended_at
                FROM crewscope.agent_run_segment
                WHERE agent_run_id = :runId ORDER BY sequence
                """,
                Map.of("runId", row.id),
                (result, index) -> new AgentRunSegment(
                        result.getLong("sequence"),
                        AgentRunSegmentKind.valueOf(result.getString("kind")),
                        Optional.ofNullable(result.getObject(
                                "resumed_from_interrupt_id", java.util.UUID.class))
                                .map(AgentInterruptId::new),
                        AgentRunSegmentStatus.valueOf(result.getString("status")),
                        io.crewscope.domain.shared.time.UtcTimestamp.from(
                                result.getObject("started_at", OffsetDateTime.class)),
                        Optional.ofNullable(result.getObject("ended_at", OffsetDateTime.class))
                                .map(io.crewscope.domain.shared.time.UtcTimestamp::from)));
        return support.mapper.toDomain(row, segments);
    }

    private record SegmentRow(java.util.UUID runId, AgentRunSegment segment) {}
}
