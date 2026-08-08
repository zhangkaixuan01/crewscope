package io.crewscope.infrastructure.persistence.workitem;

import io.crewscope.application.workitem.WorkItemTimelineEvent;
import io.crewscope.application.workitem.WorkItemTimelinePage;
import io.crewscope.application.workitem.WorkItemTimelineQuery;
import io.crewscope.application.workitem.WorkItemTimelineRepository;
import io.crewscope.application.workitem.WorkItemTimelineSource;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL query adapter that unifies and deduplicates M1 DomainEvent/Audit timeline rows. */
@Repository
public class JdbcWorkItemTimelineRepository implements WorkItemTimelineRepository {

  private static final String BASE_QUERY =
      """
      WITH candidates AS (
          SELECT event.event_id,
                 event.event_id AS domain_event_id,
                 event.event_id AS canonical_event_id,
                 2 AS source_rank,
                 'DOMAIN_EVENT' AS source,
                 event.event_type,
                 event.schema_version,
                 event.subject_type AS aggregate_type,
                 event.subject_id AS aggregate_id,
                 event.aggregate_version,
                 event.actor_type,
                 event.actor_id,
                 actor.display_name AS actor_display_name,
                 event.correlation_id,
                 event.causation_id,
                 event.occurred_at,
                 'SUCCEEDED' AS outcome,
                 event.payload::text AS payload_json
            FROM crewscope.domain_event event
            LEFT JOIN crewscope.principal actor
              ON actor.organization_id = event.organization_id
             AND actor.id = event.actor_id
           WHERE event.organization_id = :organizationId
             AND event.team_id = :teamId
             AND event.workspace_id = :workspaceId
             AND event.event_type IN (:eventTypes)
             AND ((event.subject_type = 'WORK_ITEM' AND event.subject_id = :workItemId)
                  OR event.payload ->> 'workItemId' = :workItemIdText)
          UNION ALL
          SELECT audit.event_id,
                 audit.domain_event_id,
                 COALESCE(audit.domain_event_id, audit.event_id) AS canonical_event_id,
                 1 AS source_rank,
                 'AUDIT_EVENT' AS source,
                 audit.event_type,
                 audit.schema_version,
                 audit.subject_type AS aggregate_type,
                 audit.subject_id AS aggregate_id,
                 CAST(NULL AS BIGINT) AS aggregate_version,
                 audit.actor_type,
                 audit.actor_id,
                 actor.display_name AS actor_display_name,
                 audit.correlation_id,
                 audit.causation_id,
                 audit.occurred_at,
                 audit.outcome,
                 audit.payload::text AS payload_json
            FROM crewscope.audit_event audit
            LEFT JOIN crewscope.principal actor
              ON actor.organization_id = audit.organization_id
             AND actor.id = audit.actor_id
           WHERE audit.organization_id = :organizationId
             AND audit.team_id = :teamId
             AND audit.workspace_id = :workspaceId
             AND audit.subject_id IS NOT NULL
             AND audit.event_type IN (:eventTypes)
             AND ((audit.subject_type = 'WORK_ITEM' AND audit.subject_id = :workItemId)
                  OR audit.payload ->> 'workItemId' = :workItemIdText)
      ), ranked AS (
          SELECT candidates.*,
                 ROW_NUMBER() OVER (
                     PARTITION BY canonical_event_id
                     ORDER BY source_rank DESC, event_id DESC
                 ) AS duplicate_rank
            FROM candidates
      ), canonical AS (
          SELECT *
            FROM ranked
           WHERE duplicate_rank = 1
      )
      SELECT event_id, domain_event_id, canonical_event_id, source,
             event_type, schema_version, aggregate_type, aggregate_id, aggregate_version,
             actor_type, actor_id, actor_display_name,
             correlation_id, causation_id, occurred_at, outcome, payload_json
        FROM canonical
      """;

  private final NamedParameterJdbcTemplate jdbcTemplate;

  public JdbcWorkItemTimelineRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  @Transactional(readOnly = true)
  public WorkItemTimelinePage findPage(WorkItemTimelineQuery query) {
    WorkItemTimelineQuery source = Objects.requireNonNull(query, "query");
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("organizationId", source.organizationId().value())
            .addValue("teamId", source.teamId().value())
            .addValue("workspaceId", source.workspaceId().value())
            .addValue("workItemId", source.workItemId().value())
            .addValue("workItemIdText", source.workItemId().toString())
            .addValue("eventTypes", source.visibleEventTypes())
            .addValue("fetchLimit", source.limit() + 1);
    String cursorClause = "";
    if (source.cursor().isPresent()) {
      var cursor = source.cursor().orElseThrow();
      parameters
          .addValue("afterOccurredAt", cursor.occurredAt().toOffsetDateTime())
          .addValue("afterEventId", cursor.canonicalEventId());
      cursorClause =
          """
           WHERE (occurred_at < :afterOccurredAt
                  OR (occurred_at = :afterOccurredAt AND canonical_event_id < :afterEventId))
          """;
    }
    List<WorkItemTimelineEvent> fetched =
        jdbcTemplate.query(
            BASE_QUERY
                + cursorClause
                + " ORDER BY occurred_at DESC, canonical_event_id DESC LIMIT :fetchLimit",
            parameters,
            JdbcWorkItemTimelineRepository::mapEvent);
    boolean hasMore = fetched.size() > source.limit();
    List<WorkItemTimelineEvent> items =
        hasMore ? List.copyOf(fetched.subList(0, source.limit())) : List.copyOf(fetched);
    return new WorkItemTimelinePage(
        items,
        hasMore
            ? Optional.of(items.get(items.size() - 1).cursor())
            : Optional.empty());
  }

  private static WorkItemTimelineEvent mapEvent(ResultSet resultSet, int rowNumber)
      throws SQLException {
    Long aggregateVersion = resultSet.getObject("aggregate_version", Long.class);
    UUID actorId = resultSet.getObject("actor_id", UUID.class);
    return new WorkItemTimelineEvent(
        resultSet.getObject("event_id", UUID.class),
        Optional.ofNullable(resultSet.getObject("domain_event_id", UUID.class)),
        resultSet.getObject("canonical_event_id", UUID.class),
        WorkItemTimelineSource.valueOf(resultSet.getString("source")),
        resultSet.getString("event_type"),
        resultSet.getString("schema_version"),
        resultSet.getString("aggregate_type"),
        resultSet.getObject("aggregate_id", UUID.class),
        Optional.ofNullable(aggregateVersion),
        EventActorType.valueOf(resultSet.getString("actor_type")),
        Optional.ofNullable(actorId).map(PrincipalId::new),
        Optional.ofNullable(resultSet.getString("actor_display_name")),
        resultSet.getObject("correlation_id", UUID.class),
        Optional.ofNullable(resultSet.getObject("causation_id", UUID.class)),
        UtcTimestamp.from(resultSet.getTimestamp("occurred_at").toInstant()),
        resultSet.getString("outcome"),
        resultSet.getString("payload_json"));
  }
}
