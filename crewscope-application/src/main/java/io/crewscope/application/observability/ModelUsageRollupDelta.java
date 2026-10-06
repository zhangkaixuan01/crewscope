package io.crewscope.application.observability;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One additive increment onto the monthly usage rollup grain (M10-F03): the token
 * counters, resolved costs and fact counters a single {@code ModelUsageFactRecorded}
 * contributes. Costs are {@code null} exactly when no price point resolves — the stored
 * row then carries the 'XXX' sentinel and the tokens stay visible but unbilled. An
 * unreported usage (all-zero counters) contributes {@code factCount} and
 * {@code unreportedFactCount} only, never token zeros — "the Provider did not report
 * counters" is not "the call was free".
 */
public record ModelUsageRollupDelta(
        OrganizationId organizationId,
        Optional<TeamId> teamId,
        ModelUsageMonthKey usageMonth,
        String role,
        String providerKey,
        String modelId,
        Optional<Pricing> pricing,
        int attempt,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long factCount,
        long unreportedFactCount,
        UtcTimestamp firstFactAt,
        UtcTimestamp lastFactAt) {

    /** The resolved price point: present exactly when a price row covered the fact. */
    public record Pricing(
            UUID catalogEntryId,
            long catalogRevision,
            long priceRevision,
            String currencyCode,
            BigDecimal inputCost,
            BigDecimal outputCost,
            BigDecimal cachedInputCost) {

        public Pricing {
            Objects.requireNonNull(catalogEntryId, "catalogEntryId");
            Objects.requireNonNull(currencyCode, "currencyCode");
            inputCost = requireCost(inputCost, "inputCost");
            outputCost = requireCost(outputCost, "outputCost");
            cachedInputCost = requireCost(cachedInputCost, "cachedInputCost");
        }

        private static BigDecimal requireCost(BigDecimal value, String field) {
            BigDecimal required = Objects.requireNonNull(value, field);
            if (required.signum() < 0) {
                throw new IllegalArgumentException(field + " must not be negative");
            }
            return required;
        }
    }

    public ModelUsageRollupDelta {
        Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(usageMonth, "usageMonth");
        role = Objects.requireNonNull(role, "role");
        providerKey = Objects.requireNonNull(providerKey, "providerKey");
        modelId = Objects.requireNonNull(modelId, "modelId");
        pricing = Objects.requireNonNull(pricing, "pricing");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0) {
            throw new IllegalArgumentException("token counters must be non-negative");
        }
        if (factCount < 0 || unreportedFactCount < 0 || unreportedFactCount > factCount) {
            throw new IllegalArgumentException("fact counters must satisfy 0 <= unreported <= count");
        }
        firstFactAt = Objects.requireNonNull(firstFactAt, "firstFactAt");
        lastFactAt = Objects.requireNonNull(lastFactAt, "lastFactAt");
        if (firstFactAt.value().isAfter(lastFactAt.value())) {
            throw new IllegalArgumentException("firstFactAt must not be after lastFactAt");
        }
    }
}
