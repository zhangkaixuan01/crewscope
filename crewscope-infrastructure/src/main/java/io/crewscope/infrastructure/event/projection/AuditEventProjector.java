package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.shared.event.EventActorType;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Minimal append-only security projection for every persisted DomainEvent. */
@Component
public class AuditEventProjector implements ProjectionHandler {

    public static final String PROJECTION_NAME = "audit-event-v1";

    private final JdbcTemplate jdbcTemplate;

    public AuditEventProjector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public String projectionName() {
        return PROJECTION_NAME;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void project(ProjectionEvent event) {
        ProjectionEvent source = Objects.requireNonNull(event, "event");
        Object actorId = source.actorId().orElse(null);
        Object initiatorId = source.actorType() == EventActorType.USER ? actorId : null;
        Object agentId = isAgent(source.actorType()) ? actorId : null;
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.audit_event (
                    event_id, organization_id, team_id, workspace_id,
                    principal_id, initiator_id, actor_type, actor_id, agent_principal_id,
                    credential_subject_type, credential_subject_id,
                    event_type, subject_type, subject_id, outcome,
                    authorization_context, domain_event_id,
                    correlation_id, causation_id, trace_id,
                    schema_version, occurred_at, payload
                ) VALUES (
                    ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    NULL, NULL,
                    ?, ?, ?, 'SUCCEEDED',
                    CAST('{}' AS JSONB), ?,
                    ?, ?, NULL,
                    ?, ?, CAST(? AS JSONB)
                )
                """,
                UUID.randomUUID(),
                source.organizationId(),
                source.teamId().orElse(null),
                source.workspaceId().orElse(null),
                actorId,
                initiatorId,
                source.actorType().name(),
                actorId,
                agentId,
                source.eventType(),
                source.aggregateType(),
                source.aggregateId(),
                source.eventId(),
                source.correlationId(),
                source.causationId().orElse(null),
                source.schemaVersion(),
                source.occurredAt().toOffsetDateTime(),
                source.payloadJson());
    }

    private static boolean isAgent(EventActorType actorType) {
        return actorType == EventActorType.PERSONAL_AGENT
                || actorType == EventActorType.TEAM_AGENT
                || actorType == EventActorType.SPECIALIST_AGENT;
    }
}
