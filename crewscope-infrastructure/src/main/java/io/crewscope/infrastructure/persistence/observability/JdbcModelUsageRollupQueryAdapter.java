package io.crewscope.infrastructure.persistence.observability;

import io.crewscope.application.observability.ModelUsageMonthDetailRow;
import io.crewscope.application.observability.ModelUsageMonthRoleCurrencyRow;
import io.crewscope.application.observability.ModelUsageRollupQueryRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read side of {@code crewscope.model_usage_monthly_rollup} (M10-F03). Reads run through
 * {@code ix_model_usage_rollup_team_month} (organization, team, month DESC) — the page
 * query keeps that index order, and the aggregate/detail queries stay inside one month
 * bucket per row.
 */
public class JdbcModelUsageRollupQueryAdapter implements ModelUsageRollupQueryRepository {

    private static final String MONTHS_FIRST_PAGE = """
            SELECT DISTINCT usage_month
            FROM crewscope.model_usage_monthly_rollup
            WHERE organization_id = ? AND team_id = ?
            ORDER BY usage_month DESC
            LIMIT ?""";

    private static final String MONTHS_AFTER_CURSOR = """
            SELECT DISTINCT usage_month
            FROM crewscope.model_usage_monthly_rollup
            WHERE organization_id = ? AND team_id = ? AND usage_month < ?
            ORDER BY usage_month DESC
            LIMIT ?""";

    private static final String MONTH_AGGREGATES = """
            SELECT usage_month, role, currency_code,
                   SUM(input_tokens) AS input_tokens,
                   SUM(output_tokens) AS output_tokens,
                   SUM(cached_input_tokens) AS cached_input_tokens,
                   SUM(input_cost) AS input_cost,
                   SUM(output_cost) AS output_cost,
                   SUM(cached_input_cost) AS cached_input_cost,
                   SUM(fact_count) AS fact_count,
                   SUM(unreported_fact_count) AS unreported_fact_count
            FROM crewscope.model_usage_monthly_rollup
            WHERE organization_id = ? AND team_id = ? AND usage_month IN (%s)
            GROUP BY usage_month, role, currency_code
            ORDER BY usage_month DESC, role, currency_code""";

    private static final String MONTH_DETAIL = """
            SELECT role, provider_key, model_id, currency_code,
                   catalog_revision, price_revision, attempt,
                   input_tokens, output_tokens, cached_input_tokens,
                   input_cost, output_cost, cached_input_cost,
                   fact_count, unreported_fact_count
            FROM crewscope.model_usage_monthly_rollup
            WHERE organization_id = ? AND team_id = ? AND usage_month = ?
            ORDER BY role, model_id, currency_code, price_revision NULLS LAST, attempt""";

    private final JdbcTemplate jdbcTemplate;

    public JdbcModelUsageRollupQueryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public List<String> findUsageMonths(
            OrganizationId organizationId, TeamId teamId, String after, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (after == null) {
            return jdbcTemplate.queryForList(
                    MONTHS_FIRST_PAGE, String.class,
                    organizationId.value(), teamId.value(), limit);
        }
        return jdbcTemplate.queryForList(
                MONTHS_AFTER_CURSOR, String.class,
                organizationId.value(), teamId.value(), after, limit);
    }

    @Override
    public List<ModelUsageMonthRoleCurrencyRow> findMonthAggregates(
            OrganizationId organizationId, TeamId teamId, Collection<String> usageMonths) {
        List<String> months = List.copyOf(
                Objects.requireNonNull(usageMonths, "usageMonths"));
        if (months.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(months.size(), "?"));
        Object[] arguments = new Object[2 + months.size()];
        arguments[0] = organizationId.value();
        arguments[1] = teamId.value();
        for (int index = 0; index < months.size(); index++) {
            arguments[2 + index] = months.get(index);
        }
        return jdbcTemplate.query(
                MONTH_AGGREGATES.formatted(placeholders), this::aggregateRow, arguments);
    }

    @Override
    public List<ModelUsageMonthDetailRow> findMonthDetail(
            OrganizationId organizationId, TeamId teamId, String usageMonth) {
        return jdbcTemplate.query(
                MONTH_DETAIL, this::detailRow,
                organizationId.value(), teamId.value(), usageMonth);
    }

    private ModelUsageMonthRoleCurrencyRow aggregateRow(
            ResultSet resultSet, int ignored) throws SQLException {
        return new ModelUsageMonthRoleCurrencyRow(
                resultSet.getString("usage_month"),
                resultSet.getString("role"),
                resultSet.getString("currency_code"),
                resultSet.getLong("input_tokens"),
                resultSet.getLong("output_tokens"),
                resultSet.getLong("cached_input_tokens"),
                resultSet.getBigDecimal("input_cost"),
                resultSet.getBigDecimal("output_cost"),
                resultSet.getBigDecimal("cached_input_cost"),
                resultSet.getLong("fact_count"),
                resultSet.getLong("unreported_fact_count"));
    }

    private ModelUsageMonthDetailRow detailRow(ResultSet resultSet, int ignored)
            throws SQLException {
        long catalogRevision = resultSet.getLong("catalog_revision");
        long priceRevision = resultSet.getLong("price_revision");
        return new ModelUsageMonthDetailRow(
                resultSet.getString("role"),
                resultSet.getString("provider_key"),
                resultSet.getString("model_id"),
                resultSet.getString("currency_code"),
                resultSet.getObject("catalog_revision") == null
                        ? null : catalogRevision,
                resultSet.getObject("price_revision") == null
                        ? null : priceRevision,
                resultSet.getInt("attempt"),
                resultSet.getLong("input_tokens"),
                resultSet.getLong("output_tokens"),
                resultSet.getLong("cached_input_tokens"),
                resultSet.getBigDecimal("input_cost"),
                resultSet.getBigDecimal("output_cost"),
                resultSet.getBigDecimal("cached_input_cost"),
                resultSet.getLong("fact_count"),
                resultSet.getLong("unreported_fact_count"));
    }
}
