package io.crewscope.infrastructure.persistence.organization;

import io.crewscope.application.quota.OrganizationQuotaConfigurationRepository;
import io.crewscope.domain.organization.quota.OrganizationQuotaConfiguration;
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
 * JDBC adapter for the V70 {@code crewscope.organization_quota_configuration} row. "No row"
 * is the default state (every dimension unconfigured) and stays unmaterialized until the
 * first write.
 */
@Repository
public class JdbcOrganizationQuotaConfigurationRepositoryAdapter
        implements OrganizationQuotaConfigurationRepository {

    private static final String SELECT_COLUMNS =
            "organization_id, monthly_task_intake_limit, monthly_token_budget, version,"
                    + " created_at, created_by_principal_id, updated_at, updated_by_principal_id";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrganizationQuotaConfigurationRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrganizationQuotaConfiguration> findByOrganization(OrganizationId organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        List<OrganizationQuotaConfiguration> matches =
                jdbc.query(
                        "SELECT " + SELECT_COLUMNS
                                + " FROM crewscope.organization_quota_configuration"
                                + " WHERE organization_id = :organizationId",
                        new MapSqlParameterSource("organizationId", organizationId.value()),
                        JdbcOrganizationQuotaConfigurationRepositoryAdapter::mapRow);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    @Override
    @Transactional
    public OrganizationQuotaConfiguration insert(OrganizationQuotaConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        jdbc.update(
                "INSERT INTO crewscope.organization_quota_configuration ("
                        + "organization_id, monthly_task_intake_limit, monthly_token_budget, version,"
                        + " created_at, created_by_principal_id, updated_at, updated_by_principal_id)"
                        + " VALUES (:organizationId, :intakeLimit, :tokenBudget, :version,"
                        + " :createdAt, :createdBy, :updatedAt, :updatedBy)",
                parameters(configuration));
        return configuration;
    }

    @Override
    @Transactional
    public OrganizationQuotaConfiguration update(OrganizationQuotaConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        // Optimistic concurrency against the row's committed version.
        int updated = jdbc.update(
                "UPDATE crewscope.organization_quota_configuration SET"
                        + " monthly_task_intake_limit = :intakeLimit,"
                        + " monthly_token_budget = :tokenBudget, version = :version,"
                        + " updated_at = :updatedAt, updated_by_principal_id = :updatedBy"
                        + " WHERE organization_id = :organizationId AND version = :version - 1",
                parameters(configuration));
        if (updated == 0) {
            throw new OptimisticLockConflictException(
                    "ORGANIZATION_QUOTA_CONFIGURATION",
                    configuration.organizationId().value().toString(),
                    configuration.version(),
                    configuration.version() - 1);
        }
        return configuration;
    }

    private static MapSqlParameterSource parameters(OrganizationQuotaConfiguration configuration) {
        return new MapSqlParameterSource()
                .addValue("organizationId", configuration.organizationId().value())
                .addValue("intakeLimit", configuration.monthlyTaskIntakeLimit().orElse(null))
                .addValue("tokenBudget", configuration.monthlyTokenBudget().isPresent()
                        ? configuration.monthlyTokenBudget().orElseThrow()
                        : null)
                .addValue("version", configuration.version())
                .addValue("createdAt", configuration.createdAt().toOffsetDateTime())
                .addValue("createdBy", configuration.createdBy().value())
                .addValue("updatedAt", configuration.updatedAt().toOffsetDateTime())
                .addValue("updatedBy", configuration.updatedBy().value());
    }

    private static OrganizationQuotaConfiguration mapRow(ResultSet rs, int rowNum) throws SQLException {
        return OrganizationQuotaConfiguration.reconstitute(
                new OrganizationId(rs.getObject("organization_id", java.util.UUID.class)),
                (Integer) rs.getObject("monthly_task_intake_limit"),
                (Long) rs.getObject("monthly_token_budget"),
                rs.getLong("version"),
                UtcTimestamp.from(rs.getObject("created_at", java.time.OffsetDateTime.class)),
                new PrincipalId(rs.getObject("created_by_principal_id", java.util.UUID.class)),
                UtcTimestamp.from(rs.getObject("updated_at", java.time.OffsetDateTime.class)),
                new PrincipalId(rs.getObject("updated_by_principal_id", java.util.UUID.class)));
    }
}
