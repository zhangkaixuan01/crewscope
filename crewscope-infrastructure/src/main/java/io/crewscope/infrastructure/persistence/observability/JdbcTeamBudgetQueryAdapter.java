package io.crewscope.infrastructure.persistence.observability;

import io.crewscope.application.observability.TeamBudgetQueryRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Budget-scan reads over the rollup projection (M10-F03): the active-team sweep and
 * one team's current-month totals. Token totals are input+output — cached input is a
 * subset of input; 'XXX' rows count as tokens but never enter any amount.
 */
public class JdbcTeamBudgetQueryAdapter implements TeamBudgetQueryRepository {

    private static final String ACTIVE_TEAMS = """
            SELECT organization_id, id, name
            FROM crewscope.team
            WHERE status = 'ACTIVE'
            ORDER BY organization_id, id""";

    private static final String MONTH_TOKENS = """
            SELECT COALESCE(SUM(input_tokens + output_tokens), 0) AS total_tokens
            FROM crewscope.model_usage_monthly_rollup
            WHERE organization_id = ? AND team_id = ? AND usage_month = ?""";

    private static final String MONTH_AMOUNTS = """
            SELECT currency_code, SUM(COALESCE(input_cost, 0) + COALESCE(output_cost, 0)
                   + COALESCE(cached_input_cost, 0)) AS total_cost
            FROM crewscope.model_usage_monthly_rollup
            WHERE organization_id = ? AND team_id = ? AND usage_month = ?
              AND currency_code <> 'XXX'
            GROUP BY currency_code
            ORDER BY currency_code""";

    private final JdbcTemplate jdbcTemplate;

    public JdbcTeamBudgetQueryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public List<TeamBudgetTarget> findActiveTeams() {
        return jdbcTemplate.query(ACTIVE_TEAMS, (row, ignored) -> new TeamBudgetTarget(
                new OrganizationId(row.getObject("organization_id", java.util.UUID.class)),
                new TeamId(row.getObject("id", java.util.UUID.class)),
                row.getString("name")));
    }

    @Override
    public Optional<TeamMonthlyUsage> findMonthlyUsage(
            OrganizationId organizationId, TeamId teamId, String usageMonth) {
        Object[] args = {organizationId.value(), teamId.value(), usageMonth};
        Long totalTokens = jdbcTemplate.queryForObject(
                MONTH_TOKENS, Long.class, args);
        if (totalTokens == null || totalTokens == 0) {
            // No facts at all this month: nothing to compare against any budget.
            return Optional.empty();
        }
        Map<String, BigDecimal> costs = new TreeMap<>();
        jdbcTemplate.query(MONTH_AMOUNTS, row -> {
            costs.put(row.getString("currency_code").strip(),
                    row.getBigDecimal("total_cost"));
        }, args);
        return Optional.of(new TeamMonthlyUsage(totalTokens, costs));
    }
}
