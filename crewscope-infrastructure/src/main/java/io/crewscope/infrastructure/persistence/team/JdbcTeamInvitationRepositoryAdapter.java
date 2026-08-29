package io.crewscope.infrastructure.persistence.team;

import io.crewscope.application.team.TeamInvitationCursor;
import io.crewscope.application.team.TeamInvitationExpiryService;
import io.crewscope.application.team.TeamInvitationPage;
import io.crewscope.application.team.TeamInvitationRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.InvitationTokenDigest;
import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamInvitationConflictException;
import io.crewscope.domain.team.TeamInvitationId;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Restricted JDBC adapter for V32 digest-bearing Team invitation facts. */
@Repository
public class JdbcTeamInvitationRepositoryAdapter implements TeamInvitationRepository {

    private static final int MAXIMUM_PAGE_SIZE = 200;
    private static final String SELECT = "SELECT * FROM crewscope.team_invitation";

    private final NamedParameterJdbcTemplate jdbc;
    private final TeamInvitationPersistenceMapper mapper;

    public JdbcTeamInvitationRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, TeamInvitationPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamInvitation> findById(
            OrganizationId organizationId, TeamInvitationId invitationId) {
        return findById(organizationId, invitationId, false);
    }

    @Override
    @Transactional
    public Optional<TeamInvitation> lockById(
            OrganizationId organizationId, TeamInvitationId invitationId) {
        return findById(organizationId, invitationId, true);
    }

    private Optional<TeamInvitation> findById(
            OrganizationId organizationId, TeamInvitationId invitationId, boolean lock) {
        return first(
                SELECT
                        + " WHERE organization_id = :organizationId AND id = :id"
                        + (lock ? " FOR UPDATE" : ""),
                new MapSqlParameterSource()
                        .addValue("organizationId", requireOrganization(organizationId).value())
                        .addValue("id", requireInvitationId(invitationId).value()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamInvitation> findByTokenDigest(InvitationTokenDigest tokenDigest) {
        return findByTokenDigest(tokenDigest, false);
    }

    @Override
    @Transactional
    public Optional<TeamInvitation> lockByTokenDigest(InvitationTokenDigest tokenDigest) {
        return findByTokenDigest(tokenDigest, true);
    }

    @Override
    @Transactional(readOnly = true)
    public TeamInvitationPage findByTeam(
            OrganizationId organizationId,
            TeamId teamId,
            Optional<TeamInvitationCursor> cursor,
            int limit) {
        if (limit < 1 || limit > MAXIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        OrganizationId requiredOrganization = requireOrganization(organizationId);
        TeamId requiredTeam = Objects.requireNonNull(teamId, "teamId");
        Optional<TeamInvitationCursor> requiredCursor =
                Objects.requireNonNull(cursor, "cursor");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", requiredOrganization.value())
                .addValue("teamId", requiredTeam.value())
                .addValue("limit", limit + 1);
        StringBuilder sql = new StringBuilder(SELECT)
                .append(" WHERE organization_id = :organizationId AND team_id = :teamId");
        requiredCursor.ifPresent(value -> {
            sql.append(" AND (created_at < :cursorCreatedAt")
                    .append(" OR (created_at = :cursorCreatedAt AND id < :cursorId))");
            parameters.addValue("cursorCreatedAt", timestamp(value.createdAt()));
            parameters.addValue("cursorId", value.invitationId().value());
        });
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        List<TeamInvitation> fetched =
                jdbc.query(sql.toString(), parameters, (row, ignored) -> mapper.invitation(row));
        boolean hasMore = fetched.size() > limit;
        List<TeamInvitation> visible = hasMore ? List.copyOf(fetched.subList(0, limit)) : fetched;
        Optional<TeamInvitationCursor> next = hasMore
                ? Optional.of(TeamInvitationCursor.from(visible.get(visible.size() - 1)))
                : Optional.empty();
        return new TeamInvitationPage(visible, next);
    }

    @Override
    @Transactional
    public List<TeamInvitation> lockExpiredBatch(UtcTimestamp now, int limit) {
        if (limit < 1 || limit > TeamInvitationExpiryService.MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        return jdbc.query(
                SELECT
                        + " WHERE status = 'PENDING' AND expires_at <= :now"
                        + " ORDER BY expires_at, id FOR UPDATE SKIP LOCKED LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("now", timestamp(Objects.requireNonNull(now, "now")))
                        .addValue("limit", limit),
                (row, ignored) -> mapper.invitation(row));
    }

    @Override
    @Transactional
    public TeamInvitation create(TeamInvitation invitation) {
        TeamInvitation required = Objects.requireNonNull(invitation, "invitation");
        if (required.version() != 0) {
            throw new DomainValidationException(
                    "teamInvitation.version", "must be zero when created");
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.team_invitation (
                        id, organization_id, team_id, invited_by_principal_id,
                        target_email_normalized, target_role, token_digest, expires_at,
                        status, accepted_by_account_id, accepted_member_id, resolved_at,
                        version, created_at, updated_at
                    ) VALUES (
                        :id, :organizationId, :teamId, :invitedByPrincipalId,
                        :targetEmail, :targetRole, :tokenDigest, :expiresAt,
                        :status, :acceptedByAccountId, :acceptedMemberId, :resolvedAt,
                        :version, :createdAt, :updatedAt
                    )
                    """,
                    parameters(required));
        } catch (DataIntegrityViolationException failure) {
            throw invitationConflict(failure);
        }
        return findById(required.scope().organizationId(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException("TeamInvitation", required.id()));
    }

    @Override
    @Transactional
    public TeamInvitation update(TeamInvitation invitation, long expectedVersion) {
        TeamInvitation required = Objects.requireNonNull(invitation, "invitation");
        if (expectedVersion < 0
                || expectedVersion == Long.MAX_VALUE
                || required.version() != expectedVersion + 1) {
            throw new DomainValidationException(
                    "teamInvitation.version", "must be exactly one greater than expectedVersion");
        }
        int affected;
        try {
            affected = jdbc.update(
                    """
                    UPDATE crewscope.team_invitation
                       SET status = :status,
                           accepted_by_account_id = :acceptedByAccountId,
                           accepted_member_id = :acceptedMemberId,
                           resolved_at = :resolvedAt,
                           version = :version,
                           updated_at = :updatedAt
                     WHERE organization_id = :organizationId
                       AND id = :id
                       AND version = :expectedVersion
                    """,
                    parameters(required).addValue("expectedVersion", expectedVersion));
        } catch (DataIntegrityViolationException failure) {
            throw invitationConflict(failure);
        }
        if (affected == 0) {
            throwVersionConflict(required, expectedVersion);
        }
        return findById(required.scope().organizationId(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException("TeamInvitation", required.id()));
    }

    private Optional<TeamInvitation> findByTokenDigest(
            InvitationTokenDigest tokenDigest, boolean lock) {
        InvitationTokenDigest required = Objects.requireNonNull(tokenDigest, "tokenDigest");
        return first(
                SELECT + " WHERE token_digest = :tokenDigest" + (lock ? " FOR UPDATE" : ""),
                new MapSqlParameterSource("tokenDigest", required.valueForPersistence()));
    }

    private Optional<TeamInvitation> first(String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters, (row, ignored) -> mapper.invitation(row))
                .stream()
                .findFirst();
    }

    private void throwVersionConflict(TeamInvitation invitation, long expectedVersion) {
        List<Long> versions = jdbc.query(
                "SELECT version FROM crewscope.team_invitation"
                        + " WHERE organization_id = :organizationId AND id = :id",
                new MapSqlParameterSource()
                        .addValue("organizationId", invitation.scope().organizationId().value())
                        .addValue("id", invitation.id().value()),
                (row, ignored) -> row.getLong("version"));
        if (versions.isEmpty()) {
            throw new AggregateNotFoundException("TeamInvitation", invitation.id());
        }
        throw new OptimisticLockConflictException(
                "TeamInvitation", invitation.id(), expectedVersion, versions.get(0));
    }

    private static MapSqlParameterSource parameters(TeamInvitation invitation) {
        return new MapSqlParameterSource()
                .addValue("id", invitation.id().value())
                .addValue("organizationId", invitation.scope().organizationId().value())
                .addValue("teamId", invitation.scope().teamId().value())
                .addValue("invitedByPrincipalId", invitation.invitedByPrincipalId().value())
                .addValue(
                        "targetEmail",
                        invitation.targetEmail().map(email -> email.value()).orElse(null))
                .addValue("targetRole", invitation.targetRole().name())
                .addValue("tokenDigest", invitation.tokenDigest().valueForPersistence())
                .addValue("expiresAt", timestamp(invitation.expiresAt()))
                .addValue("status", invitation.status().name())
                .addValue(
                        "acceptedByAccountId",
                        invitation.acceptedByAccountId().map(id -> id.value()).orElse(null))
                .addValue(
                        "acceptedMemberId",
                        invitation.acceptedMemberId().map(id -> id.value()).orElse(null))
                .addValue(
                        "resolvedAt",
                        invitation.resolvedAt()
                                .map(JdbcTeamInvitationRepositoryAdapter::timestamp)
                                .orElse(null))
                .addValue("version", invitation.version())
                .addValue("createdAt", timestamp(invitation.lifecycle().createdAt()))
                .addValue("updatedAt", timestamp(invitation.lifecycle().updatedAt()));
    }

    private static RuntimeException invitationConflict(DataIntegrityViolationException failure) {
        if (isInvitationConstraint(failure)) {
            return new TeamInvitationConflictException();
        }
        return failure;
    }

    private static boolean isInvitationConstraint(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && ("23503".equals(sql.getSQLState())
                            || "23505".equals(sql.getSQLState())
                            || "23514".equals(sql.getSQLState()))
                    && containsInvitationCoordinate(current.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsInvitationCoordinate(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("team_invitation")
                || normalized.contains("teaminvitation");
    }

    private static OrganizationId requireOrganization(OrganizationId value) {
        return Objects.requireNonNull(value, "organizationId");
    }

    private static TeamInvitationId requireInvitationId(TeamInvitationId value) {
        return Objects.requireNonNull(value, "invitationId");
    }

    private static OffsetDateTime timestamp(UtcTimestamp value) {
        return value.toOffsetDateTime();
    }
}
