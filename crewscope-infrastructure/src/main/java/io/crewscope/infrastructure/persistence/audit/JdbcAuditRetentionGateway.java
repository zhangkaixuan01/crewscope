package io.crewscope.infrastructure.persistence.audit;

import io.crewscope.application.organization.governance.AuditRetentionGateway;
import io.crewscope.domain.audit.AuditEventId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounded-batch audit retention deletion (M12-A01, S01 freeze 3.4). The sweep's own audit fact
 * commits in the same transaction as the deletion — the self-evidence can never lag or outlive
 * what it describes. SECURITY-category rows are the hard exception (governance self-facts); the
 * appended sweep fact is itself SECURITY and therefore never swept.
 */
@Repository
public class JdbcAuditRetentionGateway implements AuditRetentionGateway {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditRetentionGateway(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional
    public long deleteExpiredBefore(OrganizationId organizationId, UtcTimestamp cutoff, int limit) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(cutoff, "cutoff");
        if (limit <= 0) {
            return 0;
        }
        // PostgreSQL DELETE takes no LIMIT: the bounded candidate set is selected by ctid first.
        int deleted = jdbc.update(
                """
                DELETE FROM crewscope.audit_event
                 WHERE ctid IN (SELECT ctid FROM crewscope.audit_event
                                WHERE organization_id = ?
                                  AND occurred_at < ?
                                  AND event_category <> 'SECURITY'
                                LIMIT ?)
                """,
                organizationId.value(),
                cutoff.toOffsetDateTime(),
                limit);
        if (deleted == 0) {
            return 0;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("operation", "RETENTION_SWEEP");
        summary.put("deletedRows", Integer.toString(deleted));
        summary.put("cutoff", cutoff.toString());
        jdbc.update(
                """
                INSERT INTO crewscope.audit_event (
                    event_id, organization_id, team_id, principal_id, initiator_id,
                    actor_type, actor_id, agent_principal_id,
                    event_type, event_category, subject_type, subject_id, outcome,
                    authorization_context, correlation_id, schema_version,
                    retention_level, occurred_at, payload
                ) VALUES (?, ?, NULL, NULL, NULL, 'SERVICE', NULL, NULL,
                          'ORG_RETENTION_SWEEP_COMPLETED', 'SECURITY', 'ORGANIZATION', ?, 'SUCCEEDED',
                          CAST(? AS JSONB), ?, '1', 'EXTENDED', ?, CAST(? AS JSONB))
                """,
                AuditEventId.generate().value(),
                organizationId.value(),
                organizationId.value(),
                objectMapper.writeValueAsString(Map.of(
                        "classification", "REVIEWED", "result", "SUCCEEDED")),
                java.util.UUID.randomUUID(),
                UtcTimestamp.from(java.time.Instant.now()).toOffsetDateTime(),
                objectMapper.writeValueAsString(summary));
        return deleted;
    }
}
