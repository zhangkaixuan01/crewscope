package io.crewscope.infrastructure.persistence.organization;

import io.crewscope.application.quota.OrganizationQuotaUsageQueryRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only JDBC facts for the quota usage view (V70 + V64). The actual-token fold sums the
 * monthly rollup at organization grain with no team predicate on purpose: the budget admits
 * org-wide task creation, so its consumption view must not slice by team (the shared
 * findMonthAggregates port requires a team and cannot serve this query).
 */
@Repository
public class JdbcOrganizationQuotaUsageQueryRepositoryAdapter
        implements OrganizationQuotaUsageQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrganizationQuotaUsageQueryRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true)
    public long countTasksCreatedIn(
            OrganizationId organizationId, Instant startInclusive, Instant endExclusive) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(startInclusive, "startInclusive");
        Objects.requireNonNull(endExclusive, "endExclusive");
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.task"
                        + " WHERE organization_id = :organizationId"
                        + " AND created_at >= :start AND created_at < :end",
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId.value())
                        .addValue("start", startInclusive.atOffset(ZoneOffset.UTC))
                        .addValue("end", endExclusive.atOffset(ZoneOffset.UTC)),
                Long.class);
        return Objects.requireNonNull(count, "intake count");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TokenLedgerSnapshot> findTokenLedger(
            OrganizationId organizationId, String usageMonth) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(usageMonth, "usageMonth");
        List<TokenLedgerSnapshot> rows = jdbc.query(
                "SELECT reserved_tokens FROM crewscope.organization_quota_token_ledger"
                        + " WHERE organization_id = :organizationId AND usage_month = :usageMonth",
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId.value())
                        .addValue("usageMonth", usageMonth),
                (row, ignored) -> new TokenLedgerSnapshot(row.getLong("reserved_tokens")));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public ActualTokenUsage sumActualTokens(OrganizationId organizationId, String usageMonth) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(usageMonth, "usageMonth");
        List<ActualTokenUsage> rows = jdbc.query(
                """
                SELECT COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(cached_input_tokens), 0) AS cached_input_tokens,
                       COALESCE(SUM(input_tokens + output_tokens + cached_input_tokens), 0)
                           AS total_tokens
                  FROM crewscope.model_usage_monthly_rollup
                 WHERE organization_id = :organizationId AND usage_month = :usageMonth
                """,
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId.value())
                        .addValue("usageMonth", usageMonth),
                (row, ignored) -> new ActualTokenUsage(
                        row.getLong("input_tokens"),
                        row.getLong("output_tokens"),
                        row.getLong("cached_input_tokens"),
                        row.getLong("total_tokens")));
        // The aggregate query returns exactly one row even with no matching facts.
        return rows.get(0);
    }
}
