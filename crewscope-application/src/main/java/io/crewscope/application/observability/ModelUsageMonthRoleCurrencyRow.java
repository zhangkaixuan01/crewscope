package io.crewscope.application.observability;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One rollup aggregate for a reporting month grouped by source role and currency (M10-F03).
 * The grain columns (model, price triple, attempt) are already summed away at this level;
 * cost columns are null only on the 'XXX' unpriced sentinel rows.
 */
public record ModelUsageMonthRoleCurrencyRow(
        String usageMonth,
        String role,
        String currencyCode,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        BigDecimal inputCost,
        BigDecimal outputCost,
        BigDecimal cachedInputCost,
        long factCount,
        long unreportedFactCount) {

    public ModelUsageMonthRoleCurrencyRow {
        Objects.requireNonNull(usageMonth, "usageMonth");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(currencyCode, "currencyCode");
        if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0
                || factCount < 0 || unreportedFactCount < 0) {
            throw new IllegalArgumentException("usage aggregates must be non-negative");
        }
    }

    /** Total priced tokens under the rollup's own total convention (input + output). */
    public long totalTokens() {
        return Math.addExact(inputTokens, outputTokens);
    }
}
