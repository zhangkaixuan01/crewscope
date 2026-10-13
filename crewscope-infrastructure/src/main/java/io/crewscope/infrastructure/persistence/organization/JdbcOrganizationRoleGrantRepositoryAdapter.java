package io.crewscope.infrastructure.persistence.organization;

import io.crewscope.application.organization.OrganizationRoleGrantRepository;
import io.crewscope.domain.organization.role.OrganizationRoleGrant;
import io.crewscope.domain.organization.role.OrganizationRoleGrantConflictException;
import io.crewscope.domain.organization.role.OrganizationRoleGrantId;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter for the V68 {@code crewscope.organization_role_grant} table. The partial unique
 * index on ACTIVE grants is the duplicate-active boundary: a violation surfaces as the domain
 * grant conflict. Revoked rows stay as append-only history.
 */
@Repository
public class JdbcOrganizationRoleGrantRepositoryAdapter implements OrganizationRoleGrantRepository {

    private static final String SELECT_COLUMNS =
            "id, organization_id, principal_id, role, status, granted_by_principal_id,"
                    + " granted_at, revoked_at, revoked_by_principal_id, reason, version,"
                    + " created_at, created_by_principal_id, updated_at, updated_by_principal_id";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrganizationRoleGrantRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public OrganizationRoleGrant create(OrganizationRoleGrant grant) {
        Objects.requireNonNull(grant, "grant");
        try {
            jdbc.update(
                    "INSERT INTO crewscope.organization_role_grant (" + SELECT_COLUMNS + ")"
                            + " VALUES (:id, :organizationId, :principalId, :role, :status,"
                            + " :grantedBy, :grantedAt, :revokedAt, :revokedBy, :reason, :version,"
                            + " :createdAt, :createdBy, :updatedAt, :updatedBy)",
                    parameters(grant));
        } catch (DuplicateKeyException duplicateActiveGrant) {
            throw new OrganizationRoleGrantConflictException();
        }
        return grant;
    }

    @Override
    @Transactional
    public Optional<OrganizationRoleGrant> findByIdForUpdate(
            OrganizationId organizationId, OrganizationRoleGrantId grantId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(grantId, "grantId");
        List<OrganizationRoleGrant> matches =
                jdbc.query(
                        "SELECT " + SELECT_COLUMNS + " FROM crewscope.organization_role_grant"
                                + " WHERE organization_id = :organizationId AND id = :id"
                                + " FOR UPDATE",
                        new MapSqlParameterSource()
                                .addValue("organizationId", organizationId.value())
                                .addValue("id", grantId.value()),
                        JdbcOrganizationRoleGrantRepositoryAdapter::mapRow);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    @Override
    @Transactional
    public OrganizationRoleGrant update(OrganizationRoleGrant grant) {
        Objects.requireNonNull(grant, "grant");
        // Optimistic concurrency against the row's committed version.
        int updated = jdbc.update(
                "UPDATE crewscope.organization_role_grant SET"
                        + " status = :status, revoked_at = :revokedAt,"
                        + " revoked_by_principal_id = :revokedBy, reason = :reason, version = :version,"
                        + " updated_at = :updatedAt, updated_by_principal_id = :updatedBy"
                        + " WHERE id = :id AND version = :version - 1",
                parameters(grant));
        if (updated == 0) {
            throw new OptimisticLockConflictException(
                    "ORGANIZATION_ROLE_GRANT",
                    grant.id().value().toString(),
                    grant.version(),
                    grant.version() - 1);
        }
        return grant;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationRoleGrant> findByOrganization(OrganizationId organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM crewscope.organization_role_grant"
                        + " WHERE organization_id = :organizationId"
                        + " ORDER BY status, granted_at, id",
                new MapSqlParameterSource("organizationId", organizationId.value()),
                JdbcOrganizationRoleGrantRepositoryAdapter::mapRow);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationRoleGrant> findActiveByPrincipal(
            OrganizationId organizationId, PrincipalId principalId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(principalId, "principalId");
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM crewscope.organization_role_grant"
                        + " WHERE organization_id = :organizationId AND principal_id = :principalId"
                        + " AND status = 'ACTIVE'",
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId.value())
                        .addValue("principalId", principalId.value()),
                JdbcOrganizationRoleGrantRepositoryAdapter::mapRow);
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveOwners(OrganizationId organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM crewscope.organization_role_grant"
                        + " WHERE organization_id = :organizationId"
                        + " AND role = 'ORG_OWNER' AND status = 'ACTIVE'",
                new MapSqlParameterSource("organizationId", organizationId.value()),
                Long.class);
        return count == null ? 0L : count;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveOwner(OrganizationId organizationId) {
        return countActiveOwners(organizationId) > 0;
    }

    private static MapSqlParameterSource parameters(OrganizationRoleGrant grant) {
        return new MapSqlParameterSource()
                .addValue("id", grant.id().value())
                .addValue("organizationId", grant.organizationId().value())
                .addValue("principalId", grant.principalId().value())
                .addValue("role", grant.role().name())
                .addValue("status", grant.status().name())
                .addValue("grantedBy", grant.grantedBy().value())
                .addValue("grantedAt", grant.grantedAt().toOffsetDateTime())
                .addValue("revokedAt", grant.revokedAt().map(UtcTimestamp::toOffsetDateTime).orElse(null))
                .addValue("revokedBy", grant.revokedBy().map(PrincipalId::value).orElse(null))
                .addValue("reason", grant.reason().orElse(null))
                .addValue("version", grant.version())
                .addValue("createdAt", grant.createdAt().toOffsetDateTime())
                .addValue("createdBy", grant.createdBy().value())
                .addValue("updatedAt", grant.updatedAt().toOffsetDateTime())
                .addValue("updatedBy", grant.updatedBy().value());
    }

    private static OrganizationRoleGrant mapRow(ResultSet rs, int rowNum) throws SQLException {
        java.util.UUID revokedBy = rs.getObject("revoked_by_principal_id", java.util.UUID.class);
        return OrganizationRoleGrant.reconstitute(
                new OrganizationRoleGrantId(rs.getObject("id", java.util.UUID.class)),
                new OrganizationId(rs.getObject("organization_id", java.util.UUID.class)),
                new PrincipalId(rs.getObject("principal_id", java.util.UUID.class)),
                io.crewscope.domain.organization.role.OrganizationRole.valueOf(rs.getString("role")),
                OrganizationRoleGrant.GrantStatus.valueOf(rs.getString("status")),
                new PrincipalId(rs.getObject("granted_by_principal_id", java.util.UUID.class)),
                UtcTimestamp.from(rs.getObject("granted_at", java.time.OffsetDateTime.class)),
                rs.getObject("revoked_at", java.time.OffsetDateTime.class) == null
                        ? null
                        : UtcTimestamp.from(rs.getObject("revoked_at", java.time.OffsetDateTime.class)),
                revokedBy == null ? null : new PrincipalId(revokedBy),
                rs.getString("reason"),
                rs.getLong("version"),
                UtcTimestamp.from(rs.getObject("created_at", java.time.OffsetDateTime.class)),
                new PrincipalId(rs.getObject("created_by_principal_id", java.util.UUID.class)),
                UtcTimestamp.from(rs.getObject("updated_at", java.time.OffsetDateTime.class)),
                new PrincipalId(rs.getObject("updated_by_principal_id", java.util.UUID.class)));
    }
}
