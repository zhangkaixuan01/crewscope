package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.task.ClaimQuotaRepository;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL advisory-lock arbitration backed by authoritative active Lease counts. */
@Repository
public class JdbcClaimQuotaRepositoryAdapter implements ClaimQuotaRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcClaimQuotaRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision check(QuotaQuery query) {
        QuotaQuery required = Objects.requireNonNull(query, "query");
        Map<String, Object> parameters = Map.of(
                "organizationId", required.organizationId().value(),
                "teamId", required.teamId().value(),
                "environment", required.environment().value(),
                "runtimeId", required.runtimeId().value(),
                "workerId", required.workerId().value());

        // One Organization-scoped lock avoids deadlocks when a batch spans multiple Teams.
        // Active Lease rows remain the only counters, so releases and rollback cannot drift.
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:organizationId AS text), 0))",
                parameters,
                result -> {
                    result.next();
                    return null;
                });

        if (activeCount(
                        "organization_id = :organizationId AND team_id = :teamId", parameters)
                >= required.teamLimit()) {
            return Decision.TEAM_LIMIT;
        }
        if (activeCount(
                        "organization_id = :organizationId"
                                + " AND runtime_environment = :environment"
                                + " AND runtime_id = :runtimeId",
                        parameters)
                >= required.runtimeLimit()) {
            return Decision.RUNTIME_LIMIT;
        }
        if (activeCount(
                        "organization_id = :organizationId"
                                + " AND runtime_environment = :environment"
                                + " AND runtime_id = :runtimeId AND worker_id = :workerId",
                        parameters)
                >= required.workerLimit()) {
            return Decision.WORKER_LIMIT;
        }
        return Decision.AVAILABLE;
    }

    private int activeCount(String scopePredicate, Map<String, Object> parameters) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.execution_lease WHERE status = 'ACTIVE' AND "
                        + scopePredicate,
                parameters,
                Integer.class);
        return Objects.requireNonNull(count, "active Lease count");
    }
}
