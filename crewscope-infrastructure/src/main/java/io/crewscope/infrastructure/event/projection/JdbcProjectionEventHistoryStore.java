package io.crewscope.infrastructure.event.projection;

import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.event.JdbcDomainEventJsonMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Bounded keyset reader over canonical DomainEvent history and its durable Outbox envelope. */
@Repository
public class JdbcProjectionEventHistoryStore {

    public static final int MAX_PAGE_SIZE = 1_000;

    private static final String BASE_SELECT = """
            SELECT outbox.id AS outbox_id,
                   outbox.topic,
                   outbox.partition_key,
                   outbox.retry_count,
                   event.event_id,
                   event.event_type,
                   event.schema_version,
                   event.organization_id,
                   event.team_id,
                   event.workspace_id,
                   event.subject_type,
                   event.subject_id,
                   event.aggregate_version,
                   event.actor_type,
                   event.actor_id,
                   event.correlation_id,
                   event.causation_id,
                   event.idempotency_key,
                   event.occurred_at,
                   event.payload::TEXT AS payload
            FROM crewscope.domain_event event
            JOIN crewscope.outbox_event outbox
              ON outbox.domain_event_id = event.event_id
            WHERE event.organization_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcDomainEventJsonMapper eventJsonMapper;

    public JdbcProjectionEventHistoryStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.eventJsonMapper = new JdbcDomainEventJsonMapper(
                Objects.requireNonNull(objectMapper, "objectMapper"));
    }

    public ProjectionHistoryPage read(
            OrganizationId organizationId,
            Optional<ProjectionHistoryCursor> after,
            int pageSize) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        Optional<ProjectionHistoryCursor> cursor = Objects.requireNonNull(after, "after");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }

        List<ProjectionHistoryEvent> events = cursor.isEmpty()
                ? jdbcTemplate.query(
                        BASE_SELECT + """
                          ORDER BY event.subject_type, event.subject_id,
                                   event.aggregate_version, event.occurred_at, event.event_id
                          LIMIT ?
                        """,
                        this::map,
                        organization.value(),
                        pageSize)
                : readAfter(organization, cursor.orElseThrow(), pageSize);
        if (events.isEmpty()) {
            return ProjectionHistoryPage.empty();
        }
        return new ProjectionHistoryPage(
                events, Optional.of(events.get(events.size() - 1).cursor()));
    }

    private List<ProjectionHistoryEvent> readAfter(
            OrganizationId organizationId, ProjectionHistoryCursor cursor, int pageSize) {
        return jdbcTemplate.query(
                BASE_SELECT + """
                  AND (
                      event.subject_type, event.subject_id, event.aggregate_version,
                      event.occurred_at, event.event_id
                  ) > (?, ?, ?, ?, ?)
                  ORDER BY event.subject_type, event.subject_id,
                           event.aggregate_version, event.occurred_at, event.event_id
                  LIMIT ?
                """,
                this::map,
                organizationId.value(),
                cursor.aggregateType(),
                cursor.aggregateId(),
                cursor.aggregateVersion(),
                cursor.occurredAt().toOffsetDateTime(),
                cursor.eventId(),
                pageSize);
    }

    private ProjectionHistoryEvent map(ResultSet resultSet, int rowNumber) throws SQLException {
        UtcTimestamp occurredAt = UtcTimestamp.from(
                resultSet.getObject("occurred_at", OffsetDateTime.class));
        EventPublication publication = new EventPublication(
                resultSet.getObject("outbox_id", UUID.class),
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("topic"),
                resultSet.getString("partition_key"),
                resultSet.getInt("retry_count") + 1,
                occurredAt,
                eventJsonMapper.map(resultSet));
        ProjectionHistoryCursor cursor = new ProjectionHistoryCursor(
                resultSet.getString("subject_type"),
                resultSet.getObject("subject_id", UUID.class),
                resultSet.getLong("aggregate_version"),
                occurredAt,
                resultSet.getObject("event_id", UUID.class));
        return new ProjectionHistoryEvent(publication, cursor);
    }
}
