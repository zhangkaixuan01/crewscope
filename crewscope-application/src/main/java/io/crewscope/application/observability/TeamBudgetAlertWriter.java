package io.crewscope.application.observability;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamBudgetAlertKind;
import io.crewscope.domain.team.TeamBudgetAlertLevel;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistence port for the budget alert ledger (M10-F03). The insert is idempotent
 * per (organization, team, usage month, kind, level): only a first insert returns
 * {@code true}, and only then does the scan emit {@code TEAM_BUDGET_ALERT_RECORDED}.
 */
public interface TeamBudgetAlertWriter {

    boolean insertIfAbsent(AlertRow alert);

    /** The ledger row to insert; the CHECK constraints mirror these shapes. */
    record AlertRow(
            OrganizationId organizationId,
            TeamId teamId,
            String usageMonth,
            TeamBudgetAlertKind kind,
            TeamBudgetAlertLevel level,
            BigDecimal metricValue,
            BigDecimal threshold,
            Optional<String> currencyCode) {

        public AlertRow {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(teamId, "teamId");
            Objects.requireNonNull(usageMonth, "usageMonth");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(metricValue, "metricValue");
            Objects.requireNonNull(threshold, "threshold");
            if (metricValue.signum() < 0 || threshold.signum() <= 0) {
                throw new IllegalArgumentException(
                        "metricValue must be non-negative and threshold positive");
            }
            Objects.requireNonNull(currencyCode, "currencyCode");
            if (kind == TeamBudgetAlertKind.AMOUNT && currencyCode.isEmpty()) {
                throw new IllegalArgumentException("an AMOUNT alert carries a currency");
            }
            if (kind == TeamBudgetAlertKind.TOKEN && currencyCode.isPresent()) {
                throw new IllegalArgumentException("a TOKEN alert never carries a currency");
            }
        }
    }
}
