package io.crewscope.infrastructure.persistence.inbox;

import io.crewscope.application.inbox.InboxDispositionRepository;
import io.crewscope.application.inbox.InboxCounts;
import io.crewscope.application.inbox.InboxCursor;
import io.crewscope.application.inbox.InboxCursorExpiredException;
import io.crewscope.application.inbox.InboxPage;
import io.crewscope.application.inbox.InboxQuery;
import io.crewscope.application.inbox.InboxItemQueryPort;
import io.crewscope.application.inbox.InboxItemView;
import io.crewscope.application.inbox.InboxSourceTarget;
import io.crewscope.application.inbox.InboxTypeCount;
import io.crewscope.domain.inbox.InboxCloseReason;
import io.crewscope.domain.inbox.InboxDisposition;
import io.crewscope.domain.inbox.InboxDispositionStatus;
import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxPriority;
import io.crewscope.domain.inbox.InboxSource;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.inbox.InboxSourceRevision;
import io.crewscope.domain.inbox.InboxSourceType;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.infrastructure.event.projection.InboxEventProjector;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Current-Generation Inbox reader and Generation-independent Disposition authority adapter. */
@Repository
public class JdbcInboxRepositoryAdapter
        implements InboxItemQueryPort, InboxDispositionRepository {

    private static final String CURRENT_ITEM = """
            SELECT item.organization_id, item.team_id, item.member_id,
                   item.projection_name, item.generation, item.inbox_item_id,
                   item.projection_schema_version, item.item_type, item.source_type,
                   item.source_id, item.source_revision, item.priority, item.deadline,
                   item.opened_at, item.source_status, item.close_reason, item.closed_at,
                   disposition.status AS disposition_status,
                   disposition.version AS disposition_version,
                   disposition.updated_by_principal_id AS disposition_updated_by,
                   disposition.updated_at AS disposition_updated_at
            FROM crewscope.projection_pointer pointer
            JOIN crewscope.inbox_item item
              ON item.organization_id = pointer.organization_id
             AND item.projection_name = pointer.projection_name
             AND item.generation = pointer.active_generation
            LEFT JOIN crewscope.inbox_disposition disposition
              ON disposition.organization_id = item.organization_id
             AND disposition.team_id = item.team_id
             AND disposition.member_id = item.member_id
             AND disposition.inbox_item_id = item.inbox_item_id
            WHERE pointer.organization_id = ? AND pointer.projection_name = ?
              AND item.team_id = ? AND item.inbox_item_id = ?
            """;

    private static final String PRIORITY_RANK = """
            CASE item.priority
              WHEN 'URGENT' THEN 4 WHEN 'HIGH' THEN 3
              WHEN 'NORMAL' THEN 2 WHEN 'LOW' THEN 1
            END
            """;

    private final JdbcTemplate jdbc;

    public JdbcInboxRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InboxItem> findCurrent(
            OrganizationId organizationId, TeamId teamId, InboxItemId inboxItemId) {
        return currentRows(organizationId, teamId, inboxItemId).stream()
                .map(CurrentRow::item)
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InboxItemView> findCurrentView(
            OrganizationId organizationId, TeamId teamId, InboxItemId inboxItemId) {
        return currentRows(organizationId, teamId, inboxItemId).stream()
                .findFirst()
                .map(row -> InboxItemView.merge(row.item(), row.disposition()));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InboxPage findCurrentPage(InboxQuery query) {
        InboxQuery value = Objects.requireNonNull(query, "query");
        ProjectionGeneration generation = currentGeneration(value.organizationId());
        value.cursor().ifPresent(cursor -> {
            if (!cursor.generation().equals(generation)) {
                throw new InboxCursorExpiredException();
            }
        });

        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT item.organization_id, item.team_id, item.member_id,
                       item.projection_name, item.generation, item.inbox_item_id,
                       item.projection_schema_version, item.item_type, item.source_type,
                       item.source_id, item.source_revision, item.priority, item.deadline,
                       item.opened_at, item.source_status, item.close_reason, item.closed_at,
                       disposition.status AS disposition_status,
                       disposition.version AS disposition_version,
                       disposition.updated_by_principal_id AS disposition_updated_by,
                       disposition.updated_at AS disposition_updated_at
                FROM crewscope.inbox_item item
                LEFT JOIN crewscope.inbox_disposition disposition
                  ON disposition.organization_id = item.organization_id
                 AND disposition.team_id = item.team_id
                 AND disposition.member_id = item.member_id
                 AND disposition.inbox_item_id = item.inbox_item_id
                WHERE item.organization_id = ? AND item.team_id = ? AND item.member_id = ?
                  AND item.projection_name = ? AND item.generation = ?
                """);
        parameters.add(value.organizationId().value());
        parameters.add(value.teamId().value());
        parameters.add(value.memberId().value());
        parameters.add(InboxEventProjector.PROJECTION_NAME.value());
        parameters.add(generation.value());
        appendEnumFilter(sql, parameters, "item.item_type", value.filter().itemTypes());
        appendEnumFilter(sql, parameters, "item.source_status", value.filter().sourceStatuses());
        appendEnumFilter(
                sql,
                parameters,
                "COALESCE(disposition.status, 'UNREAD')",
                value.filter().dispositionStatuses());
        value.cursor().ifPresent(cursor -> appendCursor(sql, parameters, cursor));
        sql.append(" ORDER BY ").append(PRIORITY_RANK).append(" DESC, ")
                .append("item.deadline ASC NULLS LAST, item.opened_at DESC, ")
                .append("item.inbox_item_id DESC LIMIT ?");
        parameters.add(value.limit() + 1);

        List<InboxItemView> rows = jdbc.query(
                sql.toString(),
                (row, ignored) -> {
                    CurrentRow current = currentRow(row);
                    return InboxItemView.merge(current.item(), current.disposition());
                },
                parameters.toArray());
        boolean hasMore = rows.size() > value.limit();
        List<InboxItemView> items = hasMore
                ? List.copyOf(rows.subList(0, value.limit()))
                : List.copyOf(rows);
        Optional<InboxCursor> next = hasMore && !items.isEmpty()
                ? Optional.of(InboxCursor.from(items.get(items.size() - 1)))
                : Optional.empty();
        return new InboxPage(items, next);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InboxCounts countCurrent(
            OrganizationId organizationId, TeamId teamId, TeamMemberId memberId) {
        ProjectionGeneration generation = currentGeneration(organizationId);
        List<CountRow> rows = jdbc.query(
                """
                SELECT item.item_type,
                       COUNT(*) AS total_count,
                       COUNT(*) FILTER (WHERE disposition.status IS NULL) AS unread_count
                FROM crewscope.inbox_item item
                LEFT JOIN crewscope.inbox_disposition disposition
                  ON disposition.organization_id = item.organization_id
                 AND disposition.team_id = item.team_id
                 AND disposition.member_id = item.member_id
                 AND disposition.inbox_item_id = item.inbox_item_id
                WHERE item.organization_id = ? AND item.team_id = ? AND item.member_id = ?
                  AND item.projection_name = ? AND item.generation = ?
                  AND item.source_status = 'OPEN'
                  AND COALESCE(disposition.status, 'UNREAD') <> 'ARCHIVED'
                GROUP BY item.item_type
                """,
                (row, ignored) -> new CountRow(
                        InboxItemType.valueOf(row.getString("item_type")),
                        row.getLong("total_count"),
                        row.getLong("unread_count")),
                Objects.requireNonNull(organizationId, "organizationId").value(),
                Objects.requireNonNull(teamId, "teamId").value(),
                Objects.requireNonNull(memberId, "memberId").value(),
                InboxEventProjector.PROJECTION_NAME.value(),
                generation.value());
        Map<InboxItemType, InboxTypeCount> counts = new EnumMap<>(InboxItemType.class);
        rows.forEach(row -> counts.put(
                row.itemType(), new InboxTypeCount(row.total(), row.unread())));
        return new InboxCounts(counts);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InboxSourceTarget> resolveCurrentTarget(
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId,
            InboxItemId inboxItemId) {
        InboxItem item = findCurrentView(organizationId, teamId, inboxItemId)
                .filter(view -> view.item().memberId().equals(memberId))
                .map(InboxItemView::item)
                .orElse(null);
        if (item == null) {
            return Optional.empty();
        }
        UUID sourceId = item.source().key().sourceId();
        return switch (item.source().key().sourceType()) {
            case RESPONSIBILITY_ASSIGNMENT -> target(
                    """
                    SELECT project_id, work_item_id, NULL::UUID AS task_id,
                           NULL::UUID AS task_execution_id
                    FROM crewscope.responsibility_assignment
                    WHERE organization_id = ? AND team_id = ? AND id = ?
                    """,
                    InboxSourceTarget.Kind.WORK_ITEM,
                    item,
                    sourceId);
            case REVIEW_REQUEST -> target(
                    """
                    SELECT request.project_id, task.work_item_id, request.task_id,
                           request.task_execution_id
                    FROM crewscope.review_request request
                    JOIN crewscope.task task
                      ON task.organization_id = request.organization_id
                     AND task.team_id = request.team_id AND task.id = request.task_id
                    WHERE request.organization_id = ? AND request.team_id = ? AND request.id = ?
                    """,
                    InboxSourceTarget.Kind.REVIEW,
                    item,
                    sourceId);
            case ACTION_CONFIRMATION -> target(
                    """
                    SELECT project_id, work_item_id, task_id, task_execution_id
                    FROM crewscope.action_bundle
                    WHERE organization_id = ? AND team_id = ? AND id = ?
                    """,
                    InboxSourceTarget.Kind.ACTION,
                    item,
                    sourceId);
            case TASK_EXECUTION -> target(
                    """
                    SELECT execution.project_id, task.work_item_id, execution.task_id,
                           execution.id AS task_execution_id
                    FROM crewscope.task_execution execution
                    JOIN crewscope.task task
                      ON task.organization_id = execution.organization_id
                     AND task.team_id = execution.team_id AND task.id = execution.task_id
                    WHERE execution.organization_id = ? AND execution.team_id = ?
                      AND execution.id = ?
                    """,
                    InboxSourceTarget.Kind.TASK,
                    item,
                    sourceId);
            case ACTION_DELIVERY -> target(
                    """
                    SELECT bundle.project_id, bundle.work_item_id, bundle.task_id,
                           bundle.task_execution_id
                    FROM crewscope.planned_action action
                    JOIN crewscope.action_bundle bundle ON bundle.id = action.action_bundle_id
                    WHERE bundle.organization_id = ? AND bundle.team_id = ? AND action.id = ?
                    """,
                    InboxSourceTarget.Kind.ACTION,
                    item,
                    sourceId);
            case NOTIFICATION_DELIVERY -> notificationTarget(item, memberId, sourceId);
        };
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InboxDisposition> find(
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId,
            InboxItemId inboxItemId) {
        return one(jdbc.query(
                """
                SELECT organization_id, team_id, member_id, inbox_item_id,
                       status, version, updated_by_principal_id, updated_at
                FROM crewscope.inbox_disposition
                WHERE organization_id = ? AND team_id = ? AND member_id = ?
                  AND inbox_item_id = ?
                """,
                (row, ignored) -> disposition(row),
                Objects.requireNonNull(organizationId, "organizationId").value(),
                Objects.requireNonNull(teamId, "teamId").value(),
                Objects.requireNonNull(memberId, "memberId").value(),
                Objects.requireNonNull(inboxItemId, "inboxItemId").value()));
    }

    @Override
    @Transactional
    public void save(InboxDisposition disposition, long expectedVersion) {
        InboxDisposition value = Objects.requireNonNull(disposition, "disposition");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (expectedVersion == 0) {
            insert(value);
            return;
        }
        int updated = jdbc.update(
                """
                UPDATE crewscope.inbox_disposition
                SET status = ?, version = ?, updated_at = ?, updated_by_principal_id = ?
                WHERE organization_id = ? AND team_id = ? AND member_id = ?
                  AND inbox_item_id = ? AND version = ?
                """,
                value.status().name(), value.version(), value.updatedAt().toOffsetDateTime(),
                value.updatedByPrincipalId().value(), value.organizationId().value(),
                value.teamId().value(), value.memberId().value(), value.inboxItemId().value(),
                expectedVersion);
        if (updated != 1) {
            throw conflict(value, expectedVersion);
        }
    }

    private void insert(InboxDisposition value) {
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.inbox_disposition (
                        organization_id, team_id, member_id, inbox_item_id,
                        status, version, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    value.organizationId().value(), value.teamId().value(),
                    value.memberId().value(), value.inboxItemId().value(),
                    value.status().name(), value.version(), value.updatedAt().toOffsetDateTime(),
                    value.updatedByPrincipalId().value(), value.updatedAt().toOffsetDateTime(),
                    value.updatedByPrincipalId().value());
        } catch (DuplicateKeyException exception) {
            throw conflict(value, 0);
        }
    }

    private OptimisticLockConflictException conflict(
            InboxDisposition value, long expectedVersion) {
        Long actual = jdbc.query(
                """
                SELECT version FROM crewscope.inbox_disposition
                WHERE organization_id = ? AND team_id = ? AND member_id = ?
                  AND inbox_item_id = ?
                """,
                result -> result.next() ? result.getLong("version") : null,
                value.organizationId().value(), value.teamId().value(),
                value.memberId().value(), value.inboxItemId().value());
        return new OptimisticLockConflictException(
                "InboxDisposition", value.inboxItemId(), expectedVersion,
                actual == null ? 0 : actual);
    }

    private List<CurrentRow> currentRows(
            OrganizationId organizationId, TeamId teamId, InboxItemId inboxItemId) {
        List<CurrentRow> rows = jdbc.query(
                CURRENT_ITEM,
                (row, ignored) -> currentRow(row),
                Objects.requireNonNull(organizationId, "organizationId").value(),
                InboxEventProjector.PROJECTION_NAME.value(),
                Objects.requireNonNull(teamId, "teamId").value(),
                Objects.requireNonNull(inboxItemId, "inboxItemId").value());
        if (rows.size() > 1) {
            throw new IllegalStateException("Current Inbox pointer resolved multiple items");
        }
        return rows;
    }

    private ProjectionGeneration currentGeneration(OrganizationId organizationId) {
        List<Long> generations = jdbc.query(
                """
                SELECT active_generation FROM crewscope.projection_pointer
                WHERE organization_id = ? AND projection_name = ?
                """,
                (row, ignored) -> row.getLong("active_generation"),
                Objects.requireNonNull(organizationId, "organizationId").value(),
                InboxEventProjector.PROJECTION_NAME.value());
        if (generations.size() != 1) {
            throw new IllegalStateException("Current Inbox projection generation is unavailable");
        }
        return new ProjectionGeneration(generations.get(0));
    }

    private Optional<InboxSourceTarget> target(
            String sql, InboxSourceTarget.Kind kind, InboxItem item, UUID sourceId) {
        return one(jdbc.query(
                sql,
                (row, ignored) -> new InboxSourceTarget(
                        kind,
                        item.teamId(),
                        Optional.of(new io.crewscope.domain.workitem.WorkProjectId(
                                row.getObject("project_id", UUID.class))),
                        Optional.of(new io.crewscope.domain.workitem.WorkItemId(
                                row.getObject("work_item_id", UUID.class))),
                        Optional.ofNullable(row.getObject("task_id", UUID.class)),
                        Optional.ofNullable(row.getObject("task_execution_id", UUID.class)),
                        sourceId),
                item.organizationId().value(), item.teamId().value(), sourceId));
    }

    private Optional<InboxSourceTarget> notificationTarget(
            InboxItem item, TeamMemberId memberId, UUID sourceId) {
        List<UUID> values = jdbc.query(
                """
                SELECT delivery.delivery_id
                FROM crewscope.notification_delivery delivery
                JOIN crewscope.notification_planned_action action
                  ON action.organization_id = delivery.organization_id
                 AND action.action_id = delivery.action_id
                WHERE delivery.organization_id = ? AND action.team_id = ?
                  AND action.recipient_member_id = ? AND delivery.delivery_id = ?
                """,
                (row, ignored) -> row.getObject("delivery_id", UUID.class),
                item.organizationId().value(), item.teamId().value(), memberId.value(), sourceId);
        return one(values).map(ignored -> new InboxSourceTarget(
                InboxSourceTarget.Kind.NOTIFICATION,
                item.teamId(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                sourceId));
    }

    private static void appendCursor(
            StringBuilder sql, List<Object> parameters, InboxCursor cursor) {
        int rank = priorityRank(cursor.priority());
        sql.append(" AND (").append(PRIORITY_RANK).append(" < ? OR (")
                .append(PRIORITY_RANK).append(" = ? AND (");
        parameters.add(rank);
        parameters.add(rank);
        if (cursor.deadline().isPresent()) {
            sql.append("item.deadline > ? OR item.deadline IS NULL OR ")
                    .append("(item.deadline = ? AND (item.opened_at < ? OR ")
                    .append("(item.opened_at = ? AND item.inbox_item_id < ?)))");
            OffsetDateTime deadline = cursor.deadline().orElseThrow().toOffsetDateTime();
            parameters.add(deadline);
            parameters.add(deadline);
        } else {
            sql.append("item.deadline IS NULL AND (item.opened_at < ? OR ")
                    .append("(item.opened_at = ? AND item.inbox_item_id < ?))");
        }
        parameters.add(cursor.openedAt().toOffsetDateTime());
        parameters.add(cursor.openedAt().toOffsetDateTime());
        parameters.add(cursor.inboxItemId().value());
        sql.append(")))");
    }

    private static <E extends Enum<E>> void appendEnumFilter(
            StringBuilder sql, List<Object> parameters, String column, java.util.Set<E> values) {
        if (values.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (");
        int index = 0;
        for (E value : values.stream().sorted(java.util.Comparator.comparing(Enum::name)).toList()) {
            if (index++ > 0) {
                sql.append(',');
            }
            sql.append('?');
            parameters.add(value.name());
        }
        sql.append(')');
    }

    private static int priorityRank(InboxPriority priority) {
        return switch (priority) {
            case URGENT -> 4;
            case HIGH -> 3;
            case NORMAL -> 2;
            case LOW -> 1;
        };
    }

    private CurrentRow currentRow(ResultSet row) throws SQLException {
        InboxItem item = item(row);
        String status = row.getString("disposition_status");
        Optional<InboxDisposition> disposition = status == null
                ? Optional.empty()
                : Optional.of(InboxDisposition.reconstitute(
                        item.id(), item.organizationId(), item.teamId(), item.memberId(),
                        InboxDispositionStatus.valueOf(status),
                        row.getLong("disposition_version"),
                        new PrincipalId(row.getObject("disposition_updated_by", UUID.class)),
                        UtcTimestamp.from(row.getObject(
                                "disposition_updated_at", OffsetDateTime.class))));
        return new CurrentRow(item, disposition);
    }

    private InboxItem item(ResultSet row) throws SQLException {
        InboxSourceKey key = new InboxSourceKey(
                new OrganizationId(row.getObject("organization_id", UUID.class)),
                new TeamMemberId(row.getObject("member_id", UUID.class)),
                InboxItemType.valueOf(row.getString("item_type")),
                InboxSourceType.valueOf(row.getString("source_type")),
                row.getObject("source_id", UUID.class),
                new InboxSourceRevision(row.getLong("source_revision")));
        InboxSource source = InboxSource.open(
                key, InboxPriority.valueOf(row.getString("priority")),
                Optional.ofNullable(row.getObject("deadline", OffsetDateTime.class))
                        .map(UtcTimestamp::from),
                UtcTimestamp.from(row.getObject("opened_at", OffsetDateTime.class)));
        if (row.getString("source_status").equals("CLOSED")) {
            source = source.close(
                    InboxCloseReason.valueOf(row.getString("close_reason")),
                    UtcTimestamp.from(row.getObject("closed_at", OffsetDateTime.class)));
        }
        return new InboxItem(
                new InboxItemId(row.getObject("inbox_item_id", UUID.class)),
                new TeamId(row.getObject("team_id", UUID.class)),
                new ProjectionName(row.getString("projection_name")),
                new ProjectionGeneration(row.getLong("generation")),
                new SchemaVersion(row.getInt("projection_schema_version")), source);
    }

    private InboxDisposition disposition(ResultSet row) throws SQLException {
        return InboxDisposition.reconstitute(
                new InboxItemId(row.getObject("inbox_item_id", UUID.class)),
                new OrganizationId(row.getObject("organization_id", UUID.class)),
                new TeamId(row.getObject("team_id", UUID.class)),
                new TeamMemberId(row.getObject("member_id", UUID.class)),
                InboxDispositionStatus.valueOf(row.getString("status")),
                row.getLong("version"),
                new PrincipalId(row.getObject("updated_by_principal_id", UUID.class)),
                UtcTimestamp.from(row.getObject("updated_at", OffsetDateTime.class)));
    }

    private static <T> Optional<T> one(List<T> values) {
        if (values.size() > 1) {
            throw new IllegalStateException("Inbox authority query returned multiple rows");
        }
        return values.stream().findFirst();
    }

    private record CurrentRow(InboxItem item, Optional<InboxDisposition> disposition) {}

    private record CountRow(InboxItemType itemType, long total, long unread) {}
}
