package io.crewscope.infrastructure.persistence.skill;

import io.crewscope.application.skill.TeamSkillFilter;
import io.crewscope.application.skill.TeamSkillPage;
import io.crewscope.application.skill.TeamSkillPageRequest;
import io.crewscope.application.skill.TeamSkillRepository;
import io.crewscope.application.skill.TeamSkillVersionPage;
import io.crewscope.application.skill.TeamSkillVersionPageRequest;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.skill.SkillContentHash;
import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillDraft;
import io.crewscope.domain.skill.TeamSkillId;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.skill.TeamSkillOrigin;
import io.crewscope.domain.skill.TeamSkillRevision;
import io.crewscope.domain.skill.TeamSkillStatus;
import io.crewscope.domain.skill.TeamSkillVersion;
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
 * PostgreSQL store for the ADR-031 §4 skill aggregate. The head row is updated through
 * an optimistic version predicate; version rows are append-only and committed in the
 * same transaction as the head they belong to. Reads resolve the effective version by
 * joining on the authoritative head pointer, never on event order. Duplicate content
 * hashes across revisions are legal — a rollback appends historical content verbatim.
 */
@Repository
public class JdbcTeamSkillRepositoryAdapter implements TeamSkillRepository {

    private final JdbcTemplate jdbc;

    public JdbcTeamSkillRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public TeamSkill create(TeamSkill skill) {
        TeamSkill value = Objects.requireNonNull(skill, "skill");
        TeamScope scope = value.scope();
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.team_skill (
                        id, organization_id, team_id, skill_key, status,
                        effective_revision, latest_revision, disable_reason, draft_content,
                        source_task_execution_id, source_execution_attempt,
                        version, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    value.id().value(), scope.organizationId().value(), scope.teamId().value(),
                    value.skillKey().value(), value.status().name(),
                    value.effectiveRevision().map(TeamSkillRevision::value).orElse(null),
                    value.latestRevision(),
                    value.disableReason().orElse(null),
                    value.draft().map(TeamSkillDraft::content).orElse(null),
                    value.origin().map(TeamSkillOrigin::taskExecutionId).orElse(null),
                    value.origin().map(TeamSkillOrigin::attempt).orElse(null),
                    value.version(),
                    time(value.audit().createdAt()), creator(value.audit()),
                    time(value.audit().updatedAt()), updater(value.audit()));
        } catch (DataIntegrityViolationException failure) {
            throw TeamSkillPersistenceConflictMapper.skillHead(failure, scope, value.skillKey());
        }
        return value;
    }

    @Override
    @Transactional
    public TeamSkill save(TeamSkill skill, Optional<TeamSkillVersion> appendedVersion) {
        TeamSkill value = Objects.requireNonNull(skill, "skill");
        TeamScope scope = value.scope();
        long expectedVersion = value.version() - 1;
        int updated = jdbc.update(
                """
                UPDATE crewscope.team_skill
                SET status = ?, effective_revision = ?, latest_revision = ?,
                    disable_reason = ?, draft_content = ?,
                    version = ?, updated_at = ?, updated_by_principal_id = ?
                WHERE organization_id = ? AND team_id = ? AND id = ? AND version = ?
                """,
                value.status().name(),
                value.effectiveRevision().map(TeamSkillRevision::value).orElse(null),
                value.latestRevision(),
                value.disableReason().orElse(null),
                value.draft().map(TeamSkillDraft::content).orElse(null),
                value.version(),
                time(value.audit().updatedAt()), updater(value.audit()),
                scope.organizationId().value(), scope.teamId().value(),
                value.id().value(), expectedVersion);
        if (updated != 1) {
            Long actual = jdbc.queryForObject(
                    """
                    SELECT version FROM crewscope.team_skill
                    WHERE organization_id = ? AND team_id = ? AND id = ?
                    """,
                    Long.class,
                    scope.organizationId().value(), scope.teamId().value(), value.id().value());
            throw new OptimisticLockConflictException(
                    "TeamSkill", value.id(), expectedVersion, actual);
        }
        appendedVersion.ifPresent(this::insertVersion);
        return value;
    }

    @Override
    public Optional<TeamSkill> findById(
            OrganizationId organizationId, TeamId teamId, TeamSkillId skillId) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.team_skill
                WHERE organization_id = ? AND team_id = ? AND id = ?
                """,
                this::head,
                organizationId.value(), teamId.value(), skillId.value()));
    }

    @Override
    public Optional<TeamSkill> findByKey(
            OrganizationId organizationId, TeamId teamId, TeamSkillKey skillKey) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.team_skill
                WHERE organization_id = ? AND team_id = ? AND skill_key = ?
                """,
                this::head,
                organizationId.value(), teamId.value(), skillKey.value()));
    }

    @Override
    public TeamSkillPage findByTeam(
            OrganizationId organizationId,
            TeamId teamId,
            TeamSkillFilter filter,
            TeamSkillPageRequest pageRequest) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(pageRequest, "pageRequest");
        Set<TeamSkillStatus> statuses = filter.statuses();
        if (statuses.isEmpty()) {
            return new TeamSkillPage(List.of(), Optional.empty());
        }
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM crewscope.team_skill WHERE organization_id = ? AND team_id = ?");
        List<Object> parameters = new ArrayList<>(
                List.of(organizationId.value(), teamId.value()));
        sql.append(" AND status IN (")
                .append(placeholders(statuses.size())).append(')');
        parameters.addAll(statuses.stream().map(Enum::name).toList());
        pageRequest.afterSkillKey().ifPresent(after -> {
            sql.append(" AND skill_key > ?");
            parameters.add(after.value());
        });
        sql.append(" ORDER BY skill_key ASC LIMIT ").append(pageRequest.limit() + 1);
        List<TeamSkill> fetched = jdbc.query(sql.toString(), this::head, parameters.toArray());
        if (fetched.size() > pageRequest.limit()) {
            return new TeamSkillPage(
                    fetched.subList(0, pageRequest.limit()),
                    Optional.of(fetched.get(pageRequest.limit() - 1).skillKey()));
        }
        return new TeamSkillPage(fetched, Optional.empty());
    }

    @Override
    public Optional<TeamSkillVersion> findVersion(
            OrganizationId organizationId,
            TeamId teamId,
            TeamSkillId skillId,
            TeamSkillRevision revision) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.team_skill_version
                WHERE organization_id = ? AND team_id = ? AND skill_id = ? AND revision = ?
                """,
                this::version,
                organizationId.value(), teamId.value(), skillId.value(), revision.value()));
    }

    @Override
    public TeamSkillVersionPage findVersionHistory(
            OrganizationId organizationId,
            TeamId teamId,
            TeamSkillId skillId,
            TeamSkillVersionPageRequest pageRequest) {
        Objects.requireNonNull(pageRequest, "pageRequest");
        StringBuilder sql = new StringBuilder(
                """
                SELECT * FROM crewscope.team_skill_version
                WHERE organization_id = ? AND team_id = ? AND skill_id = ?
                """);
        List<Object> parameters = new ArrayList<>(
                List.of(organizationId.value(), teamId.value(), skillId.value()));
        pageRequest.afterRevision().ifPresent(after -> {
            sql.append(" AND revision > ?");
            parameters.add(after.value());
        });
        sql.append(" ORDER BY revision ASC LIMIT ").append(pageRequest.limit() + 1);
        List<TeamSkillVersion> fetched =
                jdbc.query(sql.toString(), this::version, parameters.toArray());
        if (fetched.size() > pageRequest.limit()) {
            return new TeamSkillVersionPage(
                    fetched.subList(0, pageRequest.limit()),
                    Optional.of(fetched.get(pageRequest.limit() - 1).revision()));
        }
        return new TeamSkillVersionPage(fetched, Optional.empty());
    }

    @Override
    public Optional<TeamSkillVersion> findEffectiveVersion(
            OrganizationId organizationId, TeamId teamId, TeamSkillId skillId) {
        return one(jdbc.query(
                """
                SELECT v.* FROM crewscope.team_skill s
                JOIN crewscope.team_skill_version v
                  ON v.organization_id = s.organization_id AND v.team_id = s.team_id
                 AND v.skill_id = s.id AND v.revision = s.effective_revision
                WHERE s.organization_id = ? AND s.team_id = ? AND s.id = ?
                  AND s.status = 'PUBLISHED'
                """,
                this::version,
                organizationId.value(), teamId.value(), skillId.value()));
    }

    // ---------------------------------------------------------------- internals

    private void insertVersion(TeamSkillVersion version) {
        TeamScope scope = version.scope();
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.team_skill_version (
                        organization_id, team_id, skill_id, revision, previous_revision,
                        content, content_hash, created_at, created_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    scope.organizationId().value(), scope.teamId().value(),
                    version.skillId().value(), version.revision().value(),
                    version.previousRevision().map(TeamSkillRevision::value).orElse(null),
                    version.content(), version.contentHash().value(),
                    time(version.audit().createdAt()), creator(version.audit()));
        } catch (DataIntegrityViolationException failure) {
            throw TeamSkillPersistenceConflictMapper.version(
                    failure, version.skillId(), version.revision().value());
        }
    }

    private TeamSkill head(ResultSet row, int ignored) throws SQLException {
        String draftContent = row.getString("draft_content");
        return TeamSkill.reconstitute(
                new TeamSkillId(uuid(row, "id")),
                new TeamScope(
                        new OrganizationId(uuid(row, "organization_id")),
                        new TeamId(uuid(row, "team_id"))),
                new TeamSkillKey(row.getString("skill_key")),
                origin(row),
                TeamSkillStatus.valueOf(row.getString("status")),
                optionalLong(row, "effective_revision").map(TeamSkillRevision::new),
                row.getLong("latest_revision"),
                Optional.ofNullable(draftContent).map(TeamSkillDraft::new),
                Optional.ofNullable(row.getString("disable_reason")),
                row.getLong("version"),
                audit(row));
    }

    /** The origin pair is stored fully present or fully NULL; the CHECK enforces the pairing. */
    private static Optional<TeamSkillOrigin> origin(ResultSet row) throws SQLException {
        UUID executionId = row.getObject("source_task_execution_id", UUID.class);
        if (executionId == null) {
            return Optional.empty();
        }
        return Optional.of(new TeamSkillOrigin(
                executionId, row.getInt("source_execution_attempt")));
    }

    private TeamSkillVersion version(ResultSet row, int ignored) throws SQLException {
        return TeamSkillVersion.reconstitute(
                new TeamSkillId(uuid(row, "skill_id")),
                new TeamScope(
                        new OrganizationId(uuid(row, "organization_id")),
                        new TeamId(uuid(row, "team_id"))),
                new TeamSkillRevision(row.getLong("revision")),
                optionalLong(row, "previous_revision").map(TeamSkillRevision::new),
                row.getString("content"),
                new SkillContentHash(row.getString("content_hash")),
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
            throw new IllegalStateException("Tenant-scoped TeamSkill query returned multiple rows");
        }
        return values.stream().findFirst();
    }
}
