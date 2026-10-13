package io.crewscope.infrastructure.persistence.organization;

import io.crewscope.application.organization.OrganizationSettingRepository;
import io.crewscope.domain.organization.setting.OrganizationSetting;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter for the V68 {@code crewscope.organization_setting} row. "No row" is the default
 * retention state and stays unmaterialized until the first write.
 */
@Repository
public class JdbcOrganizationSettingRepositoryAdapter implements OrganizationSettingRepository {

    private static final String SELECT_COLUMNS =
            "organization_id, audit_event_retention_days, version,"
                    + " created_at, created_by_principal_id, updated_at, updated_by_principal_id";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrganizationSettingRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrganizationSetting> findByOrganization(OrganizationId organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        List<OrganizationSetting> matches =
                jdbc.query(
                        "SELECT " + SELECT_COLUMNS
                                + " FROM crewscope.organization_setting WHERE organization_id = :organizationId",
                        new MapSqlParameterSource("organizationId", organizationId.value()),
                        JdbcOrganizationSettingRepositoryAdapter::mapRow);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationSetting> findMaterialized() {
        return jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM crewscope.organization_setting"
                        + " ORDER BY organization_id",
                JdbcOrganizationSettingRepositoryAdapter::mapRow);
    }

    @Override
    @Transactional
    public OrganizationSetting insert(OrganizationSetting setting) {
        Objects.requireNonNull(setting, "setting");
        jdbc.update(
                "INSERT INTO crewscope.organization_setting ("
                        + "organization_id, audit_event_retention_days, version,"
                        + " created_at, created_by_principal_id, updated_at, updated_by_principal_id)"
                        + " VALUES (:organizationId, :retentionDays, :version,"
                        + " :createdAt, :createdBy, :updatedAt, :updatedBy)",
                parameters(setting));
        return setting;
    }

    @Override
    @Transactional
    public OrganizationSetting update(OrganizationSetting setting) {
        Objects.requireNonNull(setting, "setting");
        // Optimistic concurrency against the row's committed version.
        int updated = jdbc.update(
                "UPDATE crewscope.organization_setting SET"
                        + " audit_event_retention_days = :retentionDays, version = :version,"
                        + " updated_at = :updatedAt, updated_by_principal_id = :updatedBy"
                        + " WHERE organization_id = :organizationId AND version = :version - 1",
                parameters(setting));
        if (updated == 0) {
            throw new OptimisticLockConflictException(
                    "ORGANIZATION_SETTING",
                    setting.organizationId().value().toString(),
                    setting.version(),
                    setting.version() - 1);
        }
        return setting;
    }

    private static MapSqlParameterSource parameters(OrganizationSetting setting) {
        return new MapSqlParameterSource()
                .addValue("organizationId", setting.organizationId().value())
                .addValue("retentionDays", setting.auditEventRetentionDays().orElse(null))
                .addValue("version", setting.version())
                .addValue("createdAt", setting.createdAt().toOffsetDateTime())
                .addValue("createdBy", setting.createdBy().value())
                .addValue("updatedAt", setting.updatedAt().toOffsetDateTime())
                .addValue("updatedBy", setting.updatedBy().value());
    }

    private static OrganizationSetting mapRow(ResultSet rs, int rowNum) throws SQLException {
        return OrganizationSetting.reconstitute(
                new OrganizationId(rs.getObject("organization_id", java.util.UUID.class)),
                (Integer) rs.getObject("audit_event_retention_days"),
                rs.getLong("version"),
                UtcTimestamp.from(rs.getObject("created_at", java.time.OffsetDateTime.class)),
                new PrincipalId(rs.getObject("created_by_principal_id", java.util.UUID.class)),
                UtcTimestamp.from(rs.getObject("updated_at", java.time.OffsetDateTime.class)),
                new PrincipalId(rs.getObject("updated_by_principal_id", java.util.UUID.class)));
    }
}
