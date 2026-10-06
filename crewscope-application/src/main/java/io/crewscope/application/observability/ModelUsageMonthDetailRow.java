package io.crewscope.application.observability;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One full-grain rollup row of a single reporting month (M10-F03): source role, model,
 * currency, price triple and attempt. Price columns are null exactly when the currency is
 * the 'XXX' unpriced sentinel.
 */
public record ModelUsageMonthDetailRow(
        String role,
        String providerKey,
        String modelId,
        String currencyCode,
        Long catalogRevision,
        Long priceRevision,
        int attempt,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        BigDecimal inputCost,
        BigDecimal outputCost,
        BigDecimal cachedInputCost,
        long factCount,
        long unreportedFactCount) {

    public ModelUsageMonthDetailRow {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(providerKey, "providerKey");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(currencyCode, "currencyCode");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0
                || factCount < 0 || unreportedFactCount < 0) {
            throw new IllegalArgumentException("usage aggregates must be non-negative");
        }
    }

    /** PRICED for real currencies, UNPRICED for the 'XXX' sentinel rows. */
    public boolean priced() {
        return !"XXX".equals(currencyCode);
    }
}
