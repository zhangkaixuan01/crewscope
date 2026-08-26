package io.crewscope.infrastructure.persistence.inbox;

import io.crewscope.application.inbox.InboxDispositionRepository;
import io.crewscope.application.inbox.InboxItemQueryPort;
import io.crewscope.application.inbox.InboxItemView;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
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
}
