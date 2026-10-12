package io.crewscope.infrastructure.persistence.organization;

import io.crewscope.application.organization.OrganizationRepository;
import io.crewscope.domain.organization.Organization;
import io.crewscope.domain.organization.OrganizationStatus;
import io.crewscope.domain.shared.id.OrganizationId;
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
 * Read-only JDBC adapter for the V1 {@code crewscope.organization} table (ADR-035: zero new
 * columns; the seeder stays the only writer on the single-organization profile).
 */
@Repository
public class JdbcOrganizationRepositoryAdapter implements OrganizationRepository {

    private static final String SELECT_BY_ID =
            "SELECT id, name, status, version, created_at, updated_at"
                    + " FROM crewscope.organization WHERE id = :id";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrganizationRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Organization> findById(OrganizationId organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        List<Organization> matches =
                jdbc.query(
                        SELECT_BY_ID,
                        new MapSqlParameterSource("id", organizationId.value()),
                        JdbcOrganizationRepositoryAdapter::mapRow);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private static Organization mapRow(ResultSet rs, int rowNum) throws SQLException {
        return Organization.reconstitute(
                new OrganizationId(rs.getObject("id", java.util.UUID.class)),
                rs.getString("name"),
                OrganizationStatus.from(rs.getString("status")),
                rs.getLong("version"),
                UtcTimestamp.from(rs.getObject("created_at", java.time.OffsetDateTime.class)),
                UtcTimestamp.from(rs.getObject("updated_at", java.time.OffsetDateTime.class)));
    }
}
