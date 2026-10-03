package io.crewscope.infrastructure.persistence.knowledge;

import io.crewscope.application.knowledge.KnowledgeEntryFilter;
import io.crewscope.application.knowledge.KnowledgeEntryPage;
import io.crewscope.application.knowledge.KnowledgeEntryPageRequest;
import io.crewscope.application.knowledge.KnowledgeEntryVersionPage;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.knowledge.KnowledgeVersionPageRequest;
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeContentHash;
import io.crewscope.domain.knowledge.KnowledgeDraft;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryOrigin;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryStatus;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL store for the ADR-030 knowledge aggregate. The head row is updated through
 * an optimistic version predicate; version rows are append-only and committed in the
 * same transaction as the head they belong to. Reads resolve the effective version by
 * joining on the authoritative head pointer, never on event order.
 */
@Repository
public class JdbcKnowledgeRepositoryAdapter implements KnowledgeRepository {

    private final JdbcTemplate jdbc;

    public JdbcKnowledgeRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public KnowledgeEntry create(KnowledgeEntry entry) {
        KnowledgeEntry value = Objects.requireNonNull(entry, "entry");
        TeamScope scope = value.scope();
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.knowledge_entry (
                        id, organization_id, team_id, entry_key, category, status,
                        effective_revision, latest_revision, draft_title, draft_content,
                        source_task_execution_id, source_execution_attempt,
                        version, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    value.id().value(), scope.organizationId().value(), scope.teamId().value(),
                    value.entryKey().value(), value.category().name(), value.status().name(),
                    value.effectiveRevision().map(KnowledgeEntryRevision::value).orElse(null),
                    value.latestRevision(),
                    value.draft().map(draft -> draft.title()).orElse(null),
                    value.draft().map(draft -> draft.content()).orElse(null),
                    value.origin().map(origin -> origin.taskExecutionId()).orElse(null),
                    value.origin().map(KnowledgeEntryOrigin::attempt).orElse(null),
                    value.version(),
                    time(value.audit().createdAt()), creator(value.audit()),
                    time(value.audit().updatedAt()), updater(value.audit()));
        } catch (DataIntegrityViolationException failure) {
            throw KnowledgePersistenceConflictMapper.entryHead(failure, scope, value.entryKey());
        }
        return value;
    }

    @Override
    @Transactional
    public KnowledgeEntry save(
            KnowledgeEntry entry, Optional<KnowledgeEntryVersion> appendedVersion) {
        KnowledgeEntry value = Objects.requireNonNull(entry, "entry");
        TeamScope scope = value.scope();
        long expectedVersion = value.version() - 1;
        int updated = jdbc.update(
                """
                UPDATE crewscope.knowledge_entry
                SET status = ?, effective_revision = ?, latest_revision = ?,
                    draft_title = ?, draft_content = ?, category = ?,
                    version = ?, updated_at = ?, updated_by_principal_id = ?
                WHERE organization_id = ? AND team_id = ? AND id = ? AND version = ?
                """,
                value.status().name(),
                value.effectiveRevision().map(KnowledgeEntryRevision::value).orElse(null),
                value.latestRevision(),
                value.draft().map(draft -> draft.title()).orElse(null),
                value.draft().map(draft -> draft.content()).orElse(null),
                value.category().name(),
                value.version(),
                time(value.audit().updatedAt()), updater(value.audit()),
                scope.organizationId().value(), scope.teamId().value(),
                value.id().value(), expectedVersion);
        if (updated != 1) {
            Long actual = jdbc.queryForObject(
                    """
                    SELECT version FROM crewscope.knowledge_entry
                    WHERE organization_id = ? AND team_id = ? AND id = ?
                    """,
                    Long.class,
                    scope.organizationId().value(), scope.teamId().value(), value.id().value());
            throw new OptimisticLockConflictException(
                    "KnowledgeEntry", value.id(), expectedVersion, actual);
        }
        appendedVersion.ifPresent(version -> insertVersion(version));
        return value;
    }

    @Override
    public Optional<KnowledgeEntry> findById(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.knowledge_entry
                WHERE organization_id = ? AND team_id = ? AND id = ?
                """,
                this::head,
                organizationId.value(), teamId.value(), entryId.value()));
    }

    @Override
    public Optional<KnowledgeEntry> findByKey(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryKey entryKey) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.knowledge_entry
                WHERE organization_id = ? AND team_id = ? AND entry_key = ?
                """,
                this::head,
                organizationId.value(), teamId.value(), entryKey.value()));
    }

    @Override
    public KnowledgeEntryPage findByTeam(
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryFilter filter,
            KnowledgeEntryPageRequest pageRequest) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(pageRequest, "pageRequest");
        Set<KnowledgeEntryStatus> statuses = filter.statuses();
        if (statuses.isEmpty()) {
            return new KnowledgeEntryPage(List.of(), Optional.empty());
        }
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM crewscope.knowledge_entry WHERE organization_id = ? AND team_id = ?");
        List<Object> parameters = new ArrayList<>(
                List.of(organizationId.value(), teamId.value()));
        sql.append(" AND status IN (")
                .append(placeholders(statuses.size())).append(')');
        parameters.addAll(statuses.stream().map(Enum::name).toList());
        filter.category().ifPresent(category -> {
            sql.append(" AND category = ?");
            parameters.add(category.name());
        });
        pageRequest.afterEntryKey().ifPresent(after -> {
            sql.append(" AND entry_key > ?");
            parameters.add(after.value());
        });
        sql.append(" ORDER BY entry_key ASC LIMIT ").append(pageRequest.limit() + 1);
        List<KnowledgeEntry> fetched = jdbc.query(sql.toString(), this::head, parameters.toArray());
        if (fetched.size() > pageRequest.limit()) {
            return new KnowledgeEntryPage(
                    fetched.subList(0, pageRequest.limit()),
                    Optional.of(fetched.get(pageRequest.limit() - 1).entryKey()));
        }
        return new KnowledgeEntryPage(fetched, Optional.empty());
    }

    @Override
    public Optional<KnowledgeEntryVersion> findVersion(
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryId entryId,
            KnowledgeEntryRevision revision) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.knowledge_entry_version
                WHERE organization_id = ? AND team_id = ? AND entry_id = ? AND revision = ?
                """,
                this::version,
                organizationId.value(), teamId.value(), entryId.value(), revision.value()));
    }

    @Override
    public KnowledgeEntryVersionPage findVersionHistory(
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryId entryId,
            KnowledgeVersionPageRequest pageRequest) {
        Objects.requireNonNull(pageRequest, "pageRequest");
        StringBuilder sql = new StringBuilder(
                """
                SELECT * FROM crewscope.knowledge_entry_version
                WHERE organization_id = ? AND team_id = ? AND entry_id = ?
                """);
        List<Object> parameters = new ArrayList<>(
                List.of(organizationId.value(), teamId.value(), entryId.value()));
        pageRequest.afterRevision().ifPresent(after -> {
            sql.append(" AND revision > ?");
            parameters.add(after.value());
        });
        sql.append(" ORDER BY revision ASC LIMIT ").append(pageRequest.limit() + 1);
        List<KnowledgeEntryVersion> fetched = jdbc.query(sql.toString(), this::version, parameters.toArray());
        if (fetched.size() > pageRequest.limit()) {
            return new KnowledgeEntryVersionPage(
                    fetched.subList(0, pageRequest.limit()),
                    Optional.of(fetched.get(pageRequest.limit() - 1).revision()));
        }
        return new KnowledgeEntryVersionPage(fetched, Optional.empty());
    }

    @Override
    public Optional<KnowledgeEntryVersion> findEffectiveVersion(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
        return one(jdbc.query(
                """
                SELECT v.* FROM crewscope.knowledge_entry e
                JOIN crewscope.knowledge_entry_version v
                  ON v.organization_id = e.organization_id AND v.team_id = e.team_id
                 AND v.entry_id = e.id AND v.revision = e.effective_revision
                WHERE e.organization_id = ? AND e.team_id = ? AND e.id = ?
                  AND e.status = 'PUBLISHED'
                """,
                this::version,
                organizationId.value(), teamId.value(), entryId.value()));
    }

    @Override
    public List<KnowledgeEntryVersion> findEffectiveVersionsByTeam(
            OrganizationId organizationId, TeamId teamId) {
        return jdbc.query(
                """
                SELECT v.* FROM crewscope.knowledge_entry e
                JOIN crewscope.knowledge_entry_version v
                  ON v.organization_id = e.organization_id AND v.team_id = e.team_id
                 AND v.entry_id = e.id AND v.revision = e.effective_revision
                WHERE e.organization_id = ? AND e.team_id = ? AND e.status = 'PUBLISHED'
                ORDER BY e.entry_key ASC, v.revision ASC
                """,
                this::version,
                organizationId.value(), teamId.value());
    }

    // ---------------------------------------------------------------- internals

    private void insertVersion(KnowledgeEntryVersion version) {
        TeamScope scope = version.scope();
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.knowledge_entry_version (
                        organization_id, team_id, entry_id, revision, previous_revision,
                        title, content, content_hash, created_at, created_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    scope.organizationId().value(), scope.teamId().value(),
                    version.entryId().value(), version.revision().value(),
                    version.previousRevision().map(KnowledgeEntryRevision::value).orElse(null),
                    version.title(), version.content(), version.contentHash().value(),
                    time(version.audit().createdAt()), creator(version.audit()));
        } catch (DataIntegrityViolationException failure) {
            throw KnowledgePersistenceConflictMapper.version(
                    failure, version.entryId(), version.revision(), version.contentHash());
        }
    }

    private KnowledgeEntry head(ResultSet row, int ignored) throws SQLException {
        String draftTitle = row.getString("draft_title");
        String draftContent = row.getString("draft_content");
        return KnowledgeEntry.reconstitute(
                new KnowledgeEntryId(uuid(row, "id")),
                new TeamScope(
                        new OrganizationId(uuid(row, "organization_id")),
                        new TeamId(uuid(row, "team_id"))),
                new KnowledgeEntryKey(row.getString("entry_key")),
                KnowledgeCategory.valueOf(row.getString("category")),
                origin(row),
                KnowledgeEntryStatus.valueOf(row.getString("status")),
                optionalLong(row, "effective_revision").map(KnowledgeEntryRevision::new),
                row.getLong("latest_revision"),
                draftTitle == null && draftContent == null
                        ? Optional.empty()
                        : Optional.of(new KnowledgeDraft(draftTitle, draftContent)),
                row.getLong("version"),
                audit(row));
    }

    /** The origin pair is stored fully present or fully NULL; the CHECK enforces the pairing. */
    private static Optional<KnowledgeEntryOrigin> origin(ResultSet row) throws SQLException {
        UUID executionId = row.getObject("source_task_execution_id", UUID.class);
        if (executionId == null) {
            return Optional.empty();
        }
        return Optional.of(new KnowledgeEntryOrigin(
                executionId, row.getInt("source_execution_attempt")));
    }

    private KnowledgeEntryVersion version(ResultSet row, int ignored) throws SQLException {
        return KnowledgeEntryVersion.reconstitute(
                new KnowledgeEntryId(uuid(row, "entry_id")),
                new TeamScope(
                        new OrganizationId(uuid(row, "organization_id")),
                        new TeamId(uuid(row, "team_id"))),
                new KnowledgeEntryRevision(row.getLong("revision")),
                optionalLong(row, "previous_revision").map(KnowledgeEntryRevision::new),
                row.getString("title"),
                row.getString("content"),
                new KnowledgeContentHash(row.getString("content_hash")),
                AuditMetadata.createdBy(
                        new PrincipalId(uuid(row, "created_by_principal_id")),
                        timestamp(row, "created_at")));
    }

    private static AuditMetadata audit(ResultSet row) throws SQLException {
        return new AuditMetadata(
                Optional.of(new PrincipalId(uuid(row, "created_by_principal_id"))),
                timestamp(row, "created_at"),
                Optional.of(new PrincipalId(uuid(row, "updated_by_principal_id"))),
                timestamp(row, "updated_at"));
    }

    private static UUID creator(AuditMetadata audit) {
        return audit.createdBy().orElseThrow().value();
    }

    private static UUID updater(AuditMetadata audit) {
        return audit.updatedBy().orElseThrow().value();
    }

    private static OffsetDateTime time(UtcTimestamp value) {
        return value.toOffsetDateTime();
    }

    private static UtcTimestamp timestamp(ResultSet row, String column) throws SQLException {
        return UtcTimestamp.from(row.getObject(column, OffsetDateTime.class).toInstant());
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static Optional<Long> optionalLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? Optional.empty() : Optional.of(value);
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static <T> Optional<T> one(List<T> values) {
        if (values.size() > 1) {
            throw new IllegalStateException("Tenant-scoped Knowledge query returned multiple rows");
        }
        return values.stream().findFirst();
    }
}
