package io.crewscope.infrastructure.persistence.conversation;

import io.crewscope.application.conversation.ConversationEvent;
import io.crewscope.application.conversation.ConversationEventCursor;
import io.crewscope.application.conversation.ConversationEventCursorExpiredException;
import io.crewscope.application.conversation.ConversationEventPage;
import io.crewscope.application.conversation.ConversationEventQuery;
import io.crewscope.application.conversation.ConversationEventRepository;
import io.crewscope.application.execution.RealtimeStreamEventIds;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.event.ConversationAssociatedEvent;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.event.StreamType;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collections;
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

/** PostgreSQL adapter for the durable Conversation Event stream index and payload join. */
@Repository
public class JdbcConversationEventRepository implements ConversationEventRepository {

  private static final String CONVERSATION_AGGREGATE = "CONVERSATION";

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JdbcConversationEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      ConversationId conversationId,
      DomainEventEnvelope<? extends DomainEvent> domainEvent) {
    ConversationId requiredConversationId =
        Objects.requireNonNull(conversationId, "conversationId");
    DomainEventEnvelope<? extends DomainEvent> source =
        Objects.requireNonNull(domainEvent, "domainEvent");
    requireAssociation(requiredConversationId, source);
    UUID streamEventId =
        RealtimeStreamEventIds.forDomain(StreamType.CONVERSATION, source.eventId());
    jdbcTemplate.update(
        """
        INSERT INTO crewscope.conversation_event (
            event_id,
            organization_id, team_id, workspace_id, conversation_id,
            domain_event_id, occurred_at, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        streamEventId,
        source.organizationId().value(),
        source.teamId().orElseThrow().value(),
        source.workspaceId().orElseThrow().value(),
        requiredConversationId.value(),
        source.eventId(),
        source.occurredAt().toOffsetDateTime(),
        source.occurredAt().toOffsetDateTime());
  }

  @Override
  @Transactional(readOnly = true)
  public ConversationEventPage findPage(ConversationEventQuery query) {
    ConversationEventQuery required = Objects.requireNonNull(query, "query");
    required.cursor().ifPresent(cursor -> requireRetained(required, cursor));
    long afterPosition = required.cursor().map(ConversationEventCursor::position).orElse(0L);
    List<ConversationEvent> rows =
        jdbcTemplate.query(
            """
            SELECT stream.position, stream.event_id AS stream_event_id,
                   stream.organization_id, stream.team_id, stream.conversation_id,
                   event.event_id AS domain_event_id,
                   event.event_type, event.schema_version,
                   event.subject_type, event.subject_id, event.aggregate_version,
                   event.correlation_id, event.causation_id,
                   event.occurred_at, event.payload::TEXT AS payload
            FROM crewscope.conversation_event stream
            JOIN crewscope.domain_event event
              ON event.organization_id = stream.organization_id
             AND event.event_id = stream.domain_event_id
            WHERE stream.organization_id = ?
              AND stream.team_id = ?
              AND stream.conversation_id = ?
              AND stream.position > ?
              AND (CAST(? AS TIMESTAMPTZ) IS NULL
                   OR stream.occurred_at <= CAST(? AS TIMESTAMPTZ))
            ORDER BY stream.position ASC
            LIMIT ?
            """,
            this::mapEvent,
            required.scope().organizationId().value(),
            required.scope().teamId().value(),
            required.conversationId().value(),
            afterPosition,
            required.visibleThrough().map(UtcTimestamp::toOffsetDateTime).orElse(null),
            required.visibleThrough().map(UtcTimestamp::toOffsetDateTime).orElse(null),
            required.limit() + 1);
    boolean hasMore = rows.size() > required.limit();
    List<ConversationEvent> page =
        hasMore ? List.copyOf(rows.subList(0, required.limit())) : List.copyOf(rows);
    return new ConversationEventPage(page, hasMore);
  }

  private void requireRetained(
      ConversationEventQuery query, ConversationEventCursor cursor) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM crewscope.conversation_event
            WHERE organization_id = ?
              AND team_id = ?
              AND conversation_id = ?
              AND position = ?
              AND event_id = ?
              AND (CAST(? AS TIMESTAMPTZ) IS NULL
                   OR occurred_at <= CAST(? AS TIMESTAMPTZ))
            """,
            Integer.class,
            query.scope().organizationId().value(),
            query.scope().teamId().value(),
            query.conversationId().value(),
            cursor.position(),
            cursor.eventId(),
            query.visibleThrough().map(UtcTimestamp::toOffsetDateTime).orElse(null),
            query.visibleThrough().map(UtcTimestamp::toOffsetDateTime).orElse(null));
    if (count == null || count != 1) {
      throw new ConversationEventCursorExpiredException();
    }
  }

  private ConversationEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
    UUID streamEventId = resultSet.getObject("stream_event_id", UUID.class);
    UUID domainEventId = resultSet.getObject("domain_event_id", UUID.class);
    UtcTimestamp occurredAt =
        UtcTimestamp.from(resultSet.getObject("occurred_at", OffsetDateTime.class));
    RealtimeEventEnvelope<Map<String, Object>> envelope =
        new RealtimeEventEnvelope<>(
            streamEventId,
            Optional.of(domainEventId),
            StreamType.CONVERSATION,
            EventType.from(resultSet.getString("event_type")),
            SchemaVersion.from(resultSet.getString("schema_version")),
            Optional.of(
                new AggregateReference(
                    resultSet.getString("subject_type"),
                    resultSet.getObject("subject_id", UUID.class))),
            Optional.of(resultSet.getLong("aggregate_version")),
            resultSet.getObject("correlation_id", UUID.class),
            Optional.ofNullable(resultSet.getObject("causation_id", UUID.class)),
            occurredAt,
            readPayload(resultSet.getString("payload")));
    ConversationEventCursor cursor =
        new ConversationEventCursor(
            new io.crewscope.domain.shared.id.OrganizationId(
                resultSet.getObject("organization_id", UUID.class)),
            new io.crewscope.domain.shared.id.TeamId(
                resultSet.getObject("team_id", UUID.class)),
            new ConversationId(resultSet.getObject("conversation_id", UUID.class)),
            resultSet.getLong("position"),
            streamEventId);
    return new ConversationEvent(cursor, envelope);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readPayload(String value) {
    Map<String, Object> decoded = objectMapper.readValue(value, Map.class);
    return Collections.unmodifiableMap(new LinkedHashMap<>(decoded));
  }

  private static void requireAssociation(
      ConversationId conversationId,
      DomainEventEnvelope<? extends DomainEvent> event) {
    if (CONVERSATION_AGGREGATE.equals(event.aggregate().type())) {
      if (!event.aggregate().id().equals(conversationId.value())) {
        throw new IllegalArgumentException("Conversation aggregate does not match stream");
      }
      return;
    }
    if (event.payload() instanceof ConversationAssociatedEvent associated
        && associated.conversationId().equals(conversationId.value())) {
      return;
    }
    throw new IllegalArgumentException("DomainEvent is not associated with the Conversation");
  }
}
