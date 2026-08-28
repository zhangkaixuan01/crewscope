package io.crewscope.infrastructure.persistence.identity;

import io.crewscope.application.identity.BootstrapOperatorLock;
import io.crewscope.application.identity.BootstrapOperatorProvisioningException;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL Organization-row lock that serializes multi-instance Operator startup. */
@Repository
public class JdbcBootstrapOperatorLock implements BootstrapOperatorLock {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBootstrapOperatorLock(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(OrganizationId organizationId) {
        var rows = jdbc.queryForList(
                "SELECT id FROM crewscope.organization WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource(
                        "id", Objects.requireNonNull(organizationId, "organizationId").value()),
                java.util.UUID.class);
        if (rows.size() != 1) {
            throw new BootstrapOperatorProvisioningException();
        }
    }
}
