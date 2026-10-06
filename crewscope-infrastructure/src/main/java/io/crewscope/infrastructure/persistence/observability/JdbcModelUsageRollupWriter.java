package io.crewscope.infrastructure.persistence.observability;

import io.crewscope.application.observability.ModelUsageRollupDelta;
import io.crewscope.application.observability.ModelUsageRollupWriter;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC adapter for the rebuildable monthly usage rollup (M10-F03). One {@link #apply}
 * call is one additive increment: the ON CONFLICT clause repeats the grain index's
 * COALESCE expressions verbatim so PostgreSQL infers {@code ux_model_usage_rollup_grain}
 * from the constant expressions, and the DO UPDATE arm adds counters and costs and
 * widens the fact window in place — concurrent increments on one grain row never lose
 * an update, and the CHECK shapes stay intact because a grain row's price triple is
 * decided by its first delta (every later delta on that grain carries the same shape).
 */
public class JdbcModelUsageRollupWriter implements ModelUsageRollupWriter {

    private final JdbcTemplate jdbcTemplate;

    public JdbcModelUsageRollupWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public void apply(ModelUsageRollupDelta delta) {
        Objects.requireNonNull(delta, "delta");
        ModelUsageRollupDelta.Pricing pricing = delta.pricing().orElse(null);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.model_usage_monthly_rollup (
                    organization_id, team_id, usage_month, role, provider_key, model_id,
                    currency_code, catalog_entry_id, catalog_revision, price_revision,
                    attempt, input_tokens, output_tokens, cached_input_tokens,
                    input_cost, output_cost, cached_input_cost,
                    fact_count, unreported_fact_count, first_fact_at, last_fact_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    organization_id,
                    COALESCE(team_id, '00000000-0000-0000-0000-000000000000'::uuid),
                    usage_month, role, provider_key, model_id, currency_code,
                    COALESCE(catalog_entry_id, '00000000-0000-0000-0000-000000000000'::uuid),
                    COALESCE(catalog_revision, -1), COALESCE(price_revision, -1), attempt)
                DO UPDATE SET
                    input_tokens = crewscope.model_usage_monthly_rollup.input_tokens
                        + EXCLUDED.input_tokens,
                    output_tokens = crewscope.model_usage_monthly_rollup.output_tokens
                        + EXCLUDED.output_tokens,
                    cached_input_tokens = crewscope.model_usage_monthly_rollup.cached_input_tokens
                        + EXCLUDED.cached_input_tokens,
                    input_cost = crewscope.model_usage_monthly_rollup.input_cost
                        + EXCLUDED.input_cost,
                    output_cost = crewscope.model_usage_monthly_rollup.output_cost
                        + EXCLUDED.output_cost,
                    cached_input_cost = crewscope.model_usage_monthly_rollup.cached_input_cost
                        + EXCLUDED.cached_input_cost,
                    fact_count = crewscope.model_usage_monthly_rollup.fact_count
                        + EXCLUDED.fact_count,
                    unreported_fact_count =
                        crewscope.model_usage_monthly_rollup.unreported_fact_count
                            + EXCLUDED.unreported_fact_count,
                    first_fact_at = LEAST(
                        crewscope.model_usage_monthly_rollup.first_fact_at,
                        EXCLUDED.first_fact_at),
                    last_fact_at = GREATEST(
                        crewscope.model_usage_monthly_rollup.last_fact_at,
                        EXCLUDED.last_fact_at)
                """,
                delta.organizationId().value(),
                delta.teamId().map(TeamId::value).orElse(null),
                delta.usageMonth().value(),
                delta.role(),
                delta.providerKey(),
                delta.modelId(),
                pricing == null ? "XXX" : pricing.currencyCode(),
                pricing == null ? null : pricing.catalogEntryId(),
                pricing == null ? null : pricing.catalogRevision(),
                pricing == null ? null : pricing.priceRevision(),
                delta.attempt(),
                delta.inputTokens(),
                delta.outputTokens(),
                delta.cachedInputTokens(),
                pricing == null ? null : pricing.inputCost(),
                pricing == null ? null : pricing.outputCost(),
                pricing == null ? null : pricing.cachedInputCost(),
                delta.factCount(),
                delta.unreportedFactCount(),
                timestamp(delta.firstFactAt()),
                timestamp(delta.lastFactAt()));
    }

    @Override
    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM crewscope.model_usage_monthly_rollup");
    }

    private static java.time.OffsetDateTime timestamp(UtcTimestamp at) {
        return java.time.OffsetDateTime.ofInstant(at.value(), ZoneOffset.UTC);
    }
}
