package io.crewscope.domain.team.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.TeamBudgetAlertId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamBudgetAlertKind;
import io.crewscope.domain.team.TeamBudgetAlertLevel;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Version 1 fact emitted once when a team crosses a soft budget line for a usage
 * month (M10-F03). One alert per (team, month, kind, level) — the emitting scan
 * inserts the ledger row with ON CONFLICT DO NOTHING and only a successful insert
 * appends this event, so repeated scans converge on one event. A reminder, never
 * a quota: nothing in the execution path observes or blocks on this fact.
 */
public record TeamBudgetAlertRecorded(
        TeamBudgetAlertId alertId,
        TeamId teamId,
        String usageMonth,
        TeamBudgetAlertKind kind,
        TeamBudgetAlertLevel level,
        BigDecimal metricValue,
        BigDecimal threshold,
        String currencyCode,
        UtcTimestamp detectedAt) implements DomainEvent {

    private static final Pattern MONTH = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public TeamBudgetAlertRecorded {
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(teamId, "teamId");
        usageMonth = requireMonth(usageMonth);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(level, "level");
        metricValue = requireNonNegative(metricValue, "metricValue");
        threshold = requirePositive(threshold, "threshold");
        currencyCode = requireCurrency(kind, currencyCode);
        Objects.requireNonNull(detectedAt, "detectedAt");
    }

    private static String requireMonth(String value) {
        String required = Objects.requireNonNull(value, "usageMonth").strip();
        if (!MONTH.matcher(required).matches()) {
            throw new IllegalArgumentException(
                    "usageMonth must be formatted as YYYY-MM");
        }
        return required;
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private static BigDecimal requirePositive(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    /** Only an AMOUNT alert is denominated; a TOKEN alert never carries a currency. */
    private static String requireCurrency(TeamBudgetAlertKind kind, String value) {
        if (kind == TeamBudgetAlertKind.AMOUNT) {
            String required = Objects.requireNonNull(value, "currencyCode")
                    .strip().toUpperCase(java.util.Locale.ROOT);
            if (!CURRENCY.matcher(required).matches()) {
                throw new IllegalArgumentException(
                        "currencyCode must be an ISO 4217 code");
            }
            // Rejects well-formed codes ISO does not know (the ModelTokenPrice precedent).
            java.util.Currency.getInstance(required);
            return required;
        }
        if (value != null) {
            throw new IllegalArgumentException("a TOKEN alert must not carry a currency");
        }
        return null;
    }
}
