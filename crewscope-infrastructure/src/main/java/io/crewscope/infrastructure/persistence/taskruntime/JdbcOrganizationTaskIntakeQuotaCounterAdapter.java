package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.application.quota.OrganizationTaskIntakeQuotaCounter;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly task-intake arbitration backed by authoritative task rows (V70 index
 * {@code ix_task_organization_created}). The task table is the only counter, so cancellations
 * keep their slot and rollback cannot strand an overcount.
 */
@Repository
public class JdbcOrganizationTaskIntakeQuotaCounterAdapter
        implements OrganizationTaskIntakeQuotaCounter {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrganizationTaskIntakeQuotaCounterAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision check(QuotaQuery query) {
        QuotaQuery required = Objects.requireNonNull(query, "query");
        Map<String, Object> parameters = Map.of(
                "organizationId", required.organizationId().value(),
                "monthWindowStart", required.monthWindowStart().atOffset(ZoneOffset.UTC),
                "monthWindowEnd", required.monthWindowEnd().atOffset(ZoneOffset.UTC));

        // Namespaced key keeps this lock independent from the claim-quota's bare-org lock.
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended("
                        + "CAST(:organizationId AS text) || ':monthly-task-intake', 0))",
                parameters,
                result -> {
                    result.next();
                    return null;
                });

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.task"
                        + " WHERE organization_id = :organizationId"
                        + " AND created_at >= :monthWindowStart AND created_at < :monthWindowEnd",
                parameters,
                Integer.class);
        return Objects.requireNonNull(count, "monthly intake count") >= required.intakeLimit()
                ? Decision.INTAKE_LIMIT_REACHED
                : Decision.AVAILABLE;
    }
}
