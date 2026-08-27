package io.crewscope.infrastructure.persistence.audit;

import io.crewscope.application.audit.AuditAccessRecord;
import io.crewscope.application.audit.AuditAccessRecorder;
import io.crewscope.domain.audit.AuditEventId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Appends safe direct security events for Audit Explorer reads and exports. */
@Repository
public class JdbcAuditAccessRecorder implements AuditAccessRecorder {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditAccessRecorder(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional
    public void record(AuditAccessRecord record) {
        AuditAccessRecord value = Objects.requireNonNull(record, "record");
        Principal actor = value.actor();
        if (!actor.scope().organizationId().equals(value.organizationId())) {
            throw new IllegalArgumentException("Audit access actor belongs to another Organization");
        }
        String eventType = switch (value.operation()) {
            case QUERY -> "AUDIT_EXPLORER_QUERIED";
            case EXPORT -> "AUDIT_EXPORT_GENERATED";
        };
        String actorType = actor.type().name();
        Object initiatorId = actor.type() == PrincipalType.USER ? actor.id().value() : null;
        Object agentId = actor.type().isAgent() ? actor.id().value() : null;
        String authorization = objectMapper.writeValueAsString(Map.of(
                "classification", "REVIEWED",
                "result", value.outcome().name()));
        // Summary values remain strings because AuditSummarySchema validates the same transport
        // shape for projected DomainEvents and direct security facts.
        String summary = objectMapper.writeValueAsString(Map.of(
                "operation", value.operation().name(),
                "result", value.outcome().name(),
                "rowCount", Integer.toString(value.rowCount())));
        jdbc.update(
                """
                INSERT INTO crewscope.audit_event (
                    event_id, organization_id, team_id, principal_id, initiator_id,
                    actor_type, actor_id, agent_principal_id,
                    event_type, event_category, subject_type, subject_id, outcome,
                    authorization_context, correlation_id, schema_version,
                    retention_level, occurred_at, payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SECURITY', 'TEAM', ?, ?,
                          CAST(? AS JSONB), ?, '1', 'EXTENDED', ?, CAST(? AS JSONB))
                """,
                AuditEventId.generate().value(),
                value.organizationId().value(),
                value.teamId().value(),
                actor.id().value(),
                initiatorId,
                actorType,
                actor.id().value(),
                agentId,
                eventType,
                value.teamId().value(),
                value.outcome().name(),
                authorization,
                value.correlationId(),
                value.occurredAt().toOffsetDateTime(),
                summary);
    }
}
