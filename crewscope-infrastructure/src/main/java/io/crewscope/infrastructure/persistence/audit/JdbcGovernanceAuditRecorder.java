package io.crewscope.infrastructure.persistence.audit;

import io.crewscope.application.organization.governance.GovernanceAuditRecord;
import io.crewscope.application.organization.governance.GovernanceAuditRecorder;
import io.crewscope.domain.audit.AuditEventId;
import io.crewscope.domain.identity.PrincipalType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends governance-plane facts (artifact downloads, deletion step settlements) as direct
 * SECURITY audit rows — the JdbcAuditAccessRecorder positioning: transport and runtime security
 * facts that never ride the DomainEvent chain because no aggregate stands behind them.
 */
@Repository
public class JdbcGovernanceAuditRecorder implements GovernanceAuditRecorder {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcGovernanceAuditRecorder(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional
    public void record(GovernanceAuditRecord record) {
        GovernanceAuditRecord value = Objects.requireNonNull(record, "record");
        if (!value.actor().scope().organizationId().equals(value.organizationId())) {
            throw new IllegalArgumentException("Governance actor belongs to another Organization");
        }
        String eventType = switch (value.operation()) {
            case EXPORT_DOWNLOAD -> "ORG_EXPORT_DOWNLOADED";
            case DELETION_STEP -> "ORG_DELETION_STEP_EXECUTED";
        };
        boolean userActor = value.actor().type() == PrincipalType.USER;
        Object initiatorId = userActor ? value.actor().id().value() : null;
        Object agentId = value.actor().type().isAgent() ? value.actor().id().value() : null;
        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("classification", "REVIEWED");
        authorization.put("result", value.outcome().name());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("operation", value.operation().name());
        summary.put("result", value.outcome().name());
        summary.put("targetRef", value.targetRef());
        summary.put("rowCount", Integer.toString(value.rowCount()));
        if (value.detail() != null) {
            summary.put("detail", value.detail());
        }
        jdbc.update(
                """
                INSERT INTO crewscope.audit_event (
                    event_id, organization_id, team_id, principal_id, initiator_id,
                    actor_type, actor_id, agent_principal_id,
                    event_type, event_category, subject_type, subject_id, outcome,
                    authorization_context, correlation_id, schema_version,
                    retention_level, occurred_at, payload
                ) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, 'SECURITY', 'ORGANIZATION', ?, ?,
                          CAST(? AS JSONB), ?, '1', 'EXTENDED', ?, CAST(? AS JSONB))
                """,
                AuditEventId.generate().value(),
                value.organizationId().value(),
                value.actor().id().value(),
                initiatorId,
                value.actor().type().name(),
                value.actor().id().value(),
                agentId,
                eventType,
                value.organizationId().value(),
                value.outcome().name(),
                objectMapper.writeValueAsString(authorization),
                value.correlationId(),
                value.occurredAt().toOffsetDateTime(),
                objectMapper.writeValueAsString(summary));
    }
}
