package io.crewscope.application.observability;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of {@code crewscope.observability.budget.*} (M10-F03). A budget of
 * zero (or a missing currency entry) leaves that dimension off — no budget, no alert.
 */
public record TeamBudgetAlertSettings(
        boolean enabled,
        long monthlyTokenBudget,
        BigDecimal warningRatio,
        Map<String, BigDecimal> monthlyAmountBudgets) {

    public TeamBudgetAlertSettings {
        if (monthlyTokenBudget < 0) {
            throw new IllegalArgumentException("monthlyTokenBudget must not be negative");
        }
        warningRatio = requireRatio(warningRatio, "warningRatio");
        monthlyAmountBudgets = Map.copyOf(Objects.requireNonNull(
                monthlyAmountBudgets, "monthlyAmountBudgets"));
    }

    /** The EXCEEDED line is the budget itself — a reminder, never a quota. */
    public BigDecimal exceededRatio() {
        return BigDecimal.ONE;
    }

    public BigDecimal tokenWarningThreshold() {
        return tokenThreshold(warningRatio);
    }

    public BigDecimal tokenExceededThreshold() {
        return tokenThreshold(exceededRatio());
    }

    public BigDecimal amountBudget(String currencyCode) {
        return monthlyAmountBudgets.get(currencyCode);
    }

    private BigDecimal tokenThreshold(BigDecimal ratio) {
        // Tokens are whole units: the warning line rounds up so it never fires late.
        return BigDecimal.valueOf(monthlyTokenBudget)
                .multiply(ratio)
                .setScale(0, java.math.RoundingMode.CEILING);
    }

    private static BigDecimal requireRatio(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() <= 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(field + " must be within (0, 1]");
        }
        return value;
    }
}
