package io.crewscope.infrastructure.persistence.observability;

import io.crewscope.application.observability.TeamBudgetAlertWriter;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The budget alert ledger insert (M10-F03): idempotent per
 * (organization, team, usage month, kind, level) — only a first insert returns
 * {@code true}, so repeated scans and concurrent scans of the same crossing
 * collapse onto one row and one event.
 */
public class JdbcTeamBudgetAlertWriter implements TeamBudgetAlertWriter {

    private static final String INSERT = """
            INSERT INTO crewscope.team_budget_alert (
                id, organization_id, team_id, usage_month, kind, level,
                metric_value, threshold, currency_code, detected_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT uk_team_budget_alert_dedup
            DO NOTHING""";

    private final JdbcTemplate jdbcTemplate;
    private final AuthoritativeTimeProvider timeProvider;

    public JdbcTeamBudgetAlertWriter(
            JdbcTemplate jdbcTemplate, AuthoritativeTimeProvider timeProvider) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    @Override
    public boolean insertIfAbsent(AlertRow alert) {
        Objects.requireNonNull(alert, "alert");
        java.time.OffsetDateTime now = timeProvider.now().toOffsetDateTime();
        return jdbcTemplate.update(INSERT,
                UUID.randomUUID(),
                alert.organizationId().value(),
                alert.teamId().value(),
                alert.usageMonth(),
                alert.kind().name(),
                alert.level().name(),
                alert.metricValue(),
                alert.threshold(),
                alert.currencyCode().orElse(null),
                now,
                now) == 1;
    }
}
