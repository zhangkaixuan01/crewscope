package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.execution.TaskRuntimeEventCommitWindow;
import io.crewscope.application.execution.TaskRuntimeEventReceipt;
import io.crewscope.application.execution.TaskRuntimeEventReceiptRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.RuntimeContentHash;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL row-lock ledger for exact AgentRun Segment event consumption. */
@Repository
public class JdbcTaskRuntimeEventReceiptRepository
        implements TaskRuntimeEventReceiptRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTaskRuntimeEventReceiptRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TaskRuntimeEventCommitWindow lockCommitWindow(
            OrganizationId organizationId,
            AgentRunId agentRunId,
            long segmentSequence,
            long eventSequence) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        AgentRunId runId = Objects.requireNonNull(agentRunId, "agentRunId");
        if (segmentSequence < 1 || eventSequence < 1) {
            throw new IllegalArgumentException("segmentSequence and eventSequence must be positive");
        }
        Map<String, Object> parameters = Map.of(
                "organizationId", organization.value(),
                "agentRunId", runId.value(),
                "segmentSequence", segmentSequence,
                "eventSequence", eventSequence);
        Boolean locked = jdbc.query(
                """
                SELECT TRUE
                FROM crewscope.agent_run
                WHERE organization_id = :organizationId AND id = :agentRunId
                FOR UPDATE
                """,
                parameters,
                result -> result.next() ? Boolean.TRUE : null);
        if (locked == null) {
            throw new AggregateNotFoundException("AgentRun", runId);
        }
        Optional<TaskRuntimeEventReceipt> existing = jdbc.query(
                """
                SELECT organization_id, agent_run_id, segment_sequence, event_sequence,
                       event_hash, runtime_event_type, domain_event_id,
                       runtime_occurred_at, recorded_at
                FROM crewscope.agent_run_event_receipt
                WHERE organization_id = :organizationId
                  AND agent_run_id = :agentRunId
                  AND segment_sequence = :segmentSequence
                  AND event_sequence = :eventSequence
                """,
                parameters,
                (result, index) -> receipt(result)).stream().findFirst();
        Long maximum = jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(event_sequence), 0)
                FROM crewscope.agent_run_event_receipt
                WHERE organization_id = :organizationId
                  AND agent_run_id = :agentRunId
                  AND segment_sequence = :segmentSequence
                """,
                parameters,
                Long.class);
        return new TaskRuntimeEventCommitWindow(
                Math.addExact(Objects.requireNonNull(maximum, "maximum"), 1), existing);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TaskRuntimeEventReceipt create(TaskRuntimeEventReceipt receipt) {
        TaskRuntimeEventReceipt value = Objects.requireNonNull(receipt, "receipt");
        jdbc.update(
                """
                INSERT INTO crewscope.agent_run_event_receipt (
                    organization_id, agent_run_id, segment_sequence, event_sequence,
                    event_hash, runtime_event_type, domain_event_id,
                    runtime_occurred_at, recorded_at
                ) VALUES (
                    :organizationId, :agentRunId, :segmentSequence, :eventSequence,
                    :eventHash, :runtimeEventType, :domainEventId,
                    :runtimeOccurredAt, :recordedAt
                )
                """,
                Map.of(
                        "organizationId", value.organizationId().value(),
                        "agentRunId", value.agentRunId().value(),
                        "segmentSequence", value.segmentSequence(),
                        "eventSequence", value.eventSequence(),
                        "eventHash", value.eventHash().value(),
                        "runtimeEventType", value.runtimeEventType(),
                        "domainEventId", value.domainEventId(),
                        "runtimeOccurredAt", value.runtimeOccurredAt().toOffsetDateTime(),
                        "recordedAt", value.recordedAt().toOffsetDateTime()));
        return value;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskRuntimeEventReceipt> find(
            OrganizationId organizationId,
            AgentRunId agentRunId,
            long segmentSequence,
            long eventSequence) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        AgentRunId runId = Objects.requireNonNull(agentRunId, "agentRunId");
        if (segmentSequence < 1 || eventSequence < 1) {
            throw new IllegalArgumentException("segmentSequence and eventSequence must be positive");
        }
        return jdbc.query(
                """
                SELECT organization_id, agent_run_id, segment_sequence, event_sequence,
                       event_hash, runtime_event_type, domain_event_id,
                       runtime_occurred_at, recorded_at
                FROM crewscope.agent_run_event_receipt
                WHERE organization_id = :organizationId
                  AND agent_run_id = :agentRunId
                  AND segment_sequence = :segmentSequence
                  AND event_sequence = :eventSequence
                """,
                Map.of(
                        "organizationId", organization.value(),
                        "agentRunId", runId.value(),
                        "segmentSequence", segmentSequence,
                        "eventSequence", eventSequence),
                (result, index) -> receipt(result)).stream().findFirst();
    }

    private static TaskRuntimeEventReceipt receipt(java.sql.ResultSet result)
            throws java.sql.SQLException {
        return new TaskRuntimeEventReceipt(
                new OrganizationId(result.getObject("organization_id", java.util.UUID.class)),
                new AgentRunId(result.getObject("agent_run_id", java.util.UUID.class)),
                result.getLong("segment_sequence"),
                result.getLong("event_sequence"),
                new RuntimeContentHash(result.getString("event_hash")),
                result.getString("runtime_event_type"),
                result.getObject("domain_event_id", java.util.UUID.class),
                UtcTimestamp.from(result.getObject("runtime_occurred_at", OffsetDateTime.class)),
                UtcTimestamp.from(result.getObject("recorded_at", OffsetDateTime.class)));
    }
}
